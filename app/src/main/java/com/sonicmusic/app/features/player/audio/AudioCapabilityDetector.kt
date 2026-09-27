package com.sonicmusic.app.features.player.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.Spatializer
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects device-level audio enhancement capabilities (Dolby Atmos, DTS, Hi-Res Audio).
 *
 * ## Detection Confidence Levels
 *
 * **HIGH confidence:**
 * - [AudioDeviceInfo.getEncodings] reporting Dolby/DTS decoder support (method a)
 * - [AudioEffect.queryEffects] finding Dolby/DTS DSP effects (method b)
 *
 * **MEDIUM confidence:**
 * - System package manager finding OEM Dolby/DTS apps (method c)
 *
 * ## Important Caveats
 *
 * Android's audio pipeline applies OEM DSP effects (Dolby Atmos, Samsung Sound Alive, etc.)
 * **after** the app's audio output reaches the HAL. There is NO public API to query whether
 * "Dolby Atmos is currently toggled ON in system settings." The best we can do is detect
 * that the *hardware/firmware* supports it. The user may still have it disabled in system
 * settings, or it may be active only for certain output routes (e.g. headphones vs speaker).
 *
 * ## Permissions
 *
 * None required. [AudioManager.getDevices], [AudioEffect.queryEffects], and
 * [PackageManager.getPackageInfo] are all public APIs with no permission guards.
 *
 * ## Threading
 *
 * Detection runs on the calling thread (fast — no I/O). The [AudioDeviceCallback] is
 * dispatched on the main looper. Results are emitted via [StateFlow] (thread-safe).
 *
 * @param context Application context (injected by Hilt)
 */
@Singleton
class AudioCapabilityDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AudioCapDetect"

        // ─── Known Dolby effect UUIDs ───────────────────────────────
        // Dolby Audio Processing (DAP) effect type UUID used by Dolby's SDK integration.
        // Source: AOSP AudioEffect documentation + Dolby SDK partner docs.
        private const val DOLBY_EFFECT_TYPE_UUID = "9d4921da-8225-4f29-aefa-39537a04bcaa"
        // Dolby DAX (Dolby Atmos for headphones) service effect UUID.
        private const val DOLBY_DAX_EFFECT_UUID = "6ab06da4-c516-4611-8166-452799218539"

        private val DOLBY_ATMOS_FEATURES = listOf(
            "dolby.hardware.dap",
            "dolby.software.dap",
        )



        // ─── Dolby-related audio encodings ──────────────────────────
        // These constants are in AudioFormat. We check getEncodings() on output devices.
        //
        // ENCODING_AC3          = Dolby Digital (DD / AC-3)
        // ENCODING_E_AC3        = Dolby Digital Plus (DD+ / E-AC-3)
        // ENCODING_E_AC3_JOC    = Dolby Atmos over DD+ (Joint Object Coding)  — API 28+
        // ENCODING_DOLBY_TRUEHD = Dolby TrueHD (lossless, Blu-ray)            — API 28+
        // ENCODING_AC4          = Dolby AC-4 (next-gen broadcast codec)        — API 29+
        //
        // Note: Most mobile devices will report ENCODING_AC3 / ENCODING_E_AC3 at minimum
        // if they have Dolby decoding. ENCODING_E_AC3_JOC is the strongest Atmos signal.

        private val DOLBY_ENCODINGS = buildSet {
            add(AudioFormat.ENCODING_AC3)        // 5 — API 21+
            add(AudioFormat.ENCODING_E_AC3)      // 6 — API 21+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(AudioFormat.ENCODING_E_AC3_JOC)    // 16 — API 28
                add(AudioFormat.ENCODING_DOLBY_TRUEHD) // 14 — API 28
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(AudioFormat.ENCODING_AC4)          // 17 — API 29
            }
        }

        // DTS-related encodings
        private val DTS_ENCODINGS = buildSet<Int> {
            add(AudioFormat.ENCODING_DTS)       // 7 — API 21+
            add(AudioFormat.ENCODING_DTS_HD)    // 8 — API 21+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // DTS-UHD added in API 34
                @Suppress("NewApi") // guarded by SDK check
                add(AudioFormat.ENCODING_DTS_UHD_P1)
                @Suppress("NewApi")
                add(AudioFormat.ENCODING_DTS_UHD_P2)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════

    /**
     * Aggregated result of all audio capability detection methods.
     */
    data class AudioCapability(
        // ── Dolby ──────────────────────────────────────────────────
        /** Method (a): Hardware decoder support via AudioDeviceInfo.getEncodings() */
        val hasDolbyDecoder: Boolean = false,
        /** Android system feature flag for Dolby Audio Processing. */
        val hasDolbySystemFeature: Boolean = false,
        /** Method (b): Dolby DSP effect found via AudioEffect.queryEffects() */
        val hasDolbyEffect: Boolean = false,
        /** Method (c): OEM Dolby app installed (e.g. com.dolby.daxservice) */
        val hasDolbyApp: Boolean = false,
        /** Method (d): Dolby audio codecs supported by system decoders */
        val hasDolbyCodec: Boolean = false,
        /** Method (e): Android Spatializer API (API 32+) */
        val hasSpatializer: Boolean = false,
        /** True when Spatializer.isAvailable() AND isEnabled() — i.e. actively spatializing */
        val spatializerEnabled: Boolean = false,
        /**
         * Non-null when Spatializer is available on this device (API 32+).
         * Value is always "Spatial Audio" — use [spatializerEnabled] for active vs. inactive.
         * Null on API < 32 or when the device has no spatial audio capability.
         */
        val spatialLabel: String? = null,
        /** True when spatial audio is actively enabled + current output device supports it */
        val spatializerActive: Boolean = false,
        /**
         * Human-readable immersive audio level from Spatializer.getImmersiveAudioLevel().
         * E.g. "Multichannel", "Over-headset object audio", or "None".
         * Empty string when Spatializer is unavailable.
         */
        val spatializerLevelName: String = "",
        /**
         * Result of Spatializer.canBeSpatialized() for a representative stereo/Opus AudioFormat.
         * False on API < 32 or when Spatializer is unavailable.
         */
        val canBeSpatializedStereoOpus: Boolean = false,
        /** Resolved display label: "Dolby Atmos" / "Dolby Audio" / null */
        val dolbyLabel: String? = null,
        /** Dolby AudioEffect Descriptor, if found */
        val dolbyEffectDescriptor: AudioEffect.Descriptor? = null,

        // ── DTS ────────────────────────────────────────────────────
        /** Hardware DTS decoder or DTS effect detected */
        val hasDts: Boolean = false,
        /** "DTS:X" / "DTS" / null */
        val dtsLabel: String? = null,

        // ── Hi-Res Audio ───────────────────────────────────────────
        /** Output device supports ≥96kHz sample rate + ≥24-bit PCM */
        val isHiResCapable: Boolean = false,
        /** "Hi-Res" / null */
        val hiResLabel: String? = null,

        /** USB audio route is present, usually an external DAC or USB headset. */
        val hasExternalDac: Boolean = false,
        /** "External DAC" / null */
        val outputLabel: String? = null,
    ) {
        /** True only when the existing capability checks positively identify Atmos support. */
        val supportsDolbyAtmos: Boolean
            get() = dolbyLabel == "Dolby Atmos"

        /**
         * True if ANY capability badge should be shown.
         * Spatial Audio is included only when the device reports availability (API 32+).
         */
        val hasAnyCapability: Boolean
            get() = dolbyLabel != null || dtsLabel != null || hiResLabel != null ||
                    outputLabel != null || spatialLabel != null

        /** Ordered list of badge labels to display (Spatial Audio last, after hardware badges) */
        val badgeLabels: List<String>
            get() = listOfNotNull(dolbyLabel, dtsLabel, hiResLabel, outputLabel, spatialLabel)
    }

    private val _capability = MutableStateFlow(AudioCapability())
    /** Observable capability state. Updates on app start and on audio device changes. */
    val capability: StateFlow<AudioCapability> = _capability.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var spatializerListener: Any? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            Log.d(TAG, "Audio device(s) added — re-detecting capabilities")
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.d(TAG, "Audio device(s) removed — re-detecting capabilities")
            refresh()
        }
    }

    init {
        // Run initial detection
        refresh()
        // Register callback for output device changes (headphones, Bluetooth, USB DAC, etc.)
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        // Register Spatializer listener for dynamic updates on API 32+
        registerSpatializerListener()
    }

    /**
     * Force a re-detection of all audio capabilities.
     * Called automatically on init and on device changes.
     */
    fun refresh() {
        val result = detectAll()
        _capability.value = result
        Log.d(TAG, "Detection result: $result")
    }

    // ═════════════════════════════════════════════════════════════════
    // DETECTION METHODS
    // ═════════════════════════════════════════════════════════════════

    private fun detectAll(): AudioCapability {
        // Only use real hardware + software signals. No guessing.
        val dolbyDecoder = detectDolbyDecoderSupport()
        val dolbyFeature = detectDolbySystemFeature()
        val dolbyEffect = detectDolbyEffect()
        val dolbyApp = detectDolbyApp()
        val dolbyCodec = detectDolbyCodecSupport()
        val spatializerResult = detectSpatializerSupport()

        val dtsDecoder = detectDtsDecoderSupport()
        val dtsEffect = detectDtsEffect()
        val dtsApp = detectDtsApp()
        val hasDts = dtsDecoder || dtsEffect || dtsApp

        val hiRes = detectHiResCapability()
        val externalDac = detectExternalDac()

        // ── Resolve Dolby label ────────────────────────────────────
        // Show badge ONLY if hardware or software actually confirms Dolby.
        // No Dolby detected = no badge. Simple.
        val hasAtmosEvidence = dolbyDecoder.hasAtmos || 
                dolbyFeature.isAtmos || 
                dolbyEffect.isAtmos || 
                dolbyApp.isAtmos ||
                (spatializerResult.isAvailable && spatializerResult.immersiveLevel != 0)

        val hasAnyDolby = dolbyDecoder.hasAny || 
                dolbyFeature.found || 
                dolbyEffect.found || 
                dolbyApp.found ||
                dolbyCodec ||
                spatializerResult.isAvailable

        val dolbyLabel = when {
            hasAtmosEvidence -> "Dolby Atmos"
            hasAnyDolby -> "Dolby Audio"
            else -> null
        }

        val dtsLabel = when {
            dtsEffect -> "DTS:X"
            dtsDecoder || dtsApp -> "DTS"
            else -> null
        }

        val hiResLabel = if (hiRes && !externalDac) "Hi-Res" else null
        val outputLabel = if (externalDac) "External DAC" else null

        // ── Resolve Spatial Audio label ────────────────────────────
        // Only show when Spatializer API reports this device as capable (API 32+).
        // Null = badge hidden entirely (API < 32 or no capability).
        val spatialLabel = if (spatializerResult.isAvailable) "Spatial Audio" else null
        val spatializerActive = spatializerResult.isAvailable && spatializerResult.isEnabled

        return AudioCapability(
            hasDolbyDecoder = dolbyDecoder.hasAny,
            hasDolbySystemFeature = dolbyFeature.found,
            hasDolbyEffect = dolbyEffect.found,
            hasDolbyApp = dolbyApp.found,
            hasDolbyCodec = dolbyCodec,
            hasSpatializer = spatializerResult.isAvailable,
            spatializerEnabled = spatializerResult.isEnabled,
            spatialLabel = spatialLabel,
            spatializerActive = spatializerActive,
            spatializerLevelName = spatializerResult.immersiveLevelName,
            canBeSpatializedStereoOpus = spatializerResult.canBeSpatializedStereo,
            dolbyLabel = dolbyLabel,
            dolbyEffectDescriptor = dolbyEffect.descriptor,
            hasDts = hasDts,
            dtsLabel = dtsLabel,
            isHiResCapable = hiRes,
            hiResLabel = hiResLabel,
            hasExternalDac = externalDac,
            outputLabel = outputLabel,
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // Method (a): Hardware decoder support via AudioDeviceInfo encodings
    // CONFIDENCE: HIGH
    //
    // Checks all output AudioDeviceInfo for Dolby-related encoding support.
    // On most Snapdragon/Exynos SoCs with Dolby licensing, the audio HAL
    // reports E_AC3_JOC (Atmos) or at minimum AC3/E_AC3 support.
    // ─────────────────────────────────────────────────────────────────
    private data class DecoderResult(val hasAny: Boolean, val hasAtmos: Boolean)

    private fun detectDolbyDecoderSupport(): DecoderResult {
        return try {
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            var hasAnyDolby = false
            var hasAtmos = false

            for (device in outputDevices) {
                val encodings = device.encodings
                if (encodings.isEmpty()) continue

                for (encoding in encodings) {
                    if (encoding in DOLBY_ENCODINGS) {
                        hasAnyDolby = true
                        // E_AC3_JOC is the definitive Atmos signal
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                            encoding == AudioFormat.ENCODING_E_AC3_JOC
                        ) {
                            hasAtmos = true
                        }
                        // AC-4 also often implies Atmos capability
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            encoding == AudioFormat.ENCODING_AC4
                        ) {
                            hasAtmos = true
                        }
                    }
                }
            }

            Log.d(TAG, "  (a) Dolby decoder: any=$hasAnyDolby, atmos=$hasAtmos")
            DecoderResult(hasAnyDolby, hasAtmos)
        } catch (e: Exception) {
            Log.w(TAG, "  (a) Dolby decoder check failed", e)
            DecoderResult(false, false)
        }
    }

    private fun detectDtsDecoderSupport(): Boolean {
        return try {
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in outputDevices) {
                val encodings = device.encodings
                if (encodings.isEmpty()) continue
                if (encodings.any { it in DTS_ENCODINGS }) {
                    Log.d(TAG, "  (a) DTS decoder: found")
                    return true
                }
            }
            Log.d(TAG, "  (a) DTS decoder: not found")
            false
        } catch (e: Exception) {
            Log.w(TAG, "  (a) DTS decoder check failed", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Method (b): System audio effects via AudioEffect.queryEffects()
    // CONFIDENCE: HIGH
    //
    // Iterates all registered AudioEffect descriptors. OEM Dolby integration
    // registers a system-level effect whose name/uuid matches known Dolby IDs.
    // Falls back to case-insensitive name matching ("dolby", "dax") as best-effort.
    // ─────────────────────────────────────────────────────────────────
    private data class FeatureResult(val found: Boolean, val isAtmos: Boolean)

    private fun detectDolbySystemFeature(): FeatureResult {
        return try {
            val pm = context.packageManager
            val hasDolbyAtmos = DOLBY_ATMOS_FEATURES.any { pm.hasSystemFeature(it) }
            Log.d(TAG, "  (feature) Dolby DAP: atmos=$hasDolbyAtmos")
            FeatureResult(found = hasDolbyAtmos, isAtmos = hasDolbyAtmos)
        } catch (e: Exception) {
            Log.w(TAG, "  (feature) Dolby feature check failed", e)
            FeatureResult(found = false, isAtmos = false)
        }
    }

    private data class EffectResult(
        val found: Boolean,
        val isAtmos: Boolean,
        val descriptor: AudioEffect.Descriptor? = null
    )

    private fun detectDolbyEffect(): EffectResult {
        return try {
            val effects = AudioEffect.queryEffects() ?: emptyArray()
            var found = false
            var isAtmos = false
            var matchedDescriptor: AudioEffect.Descriptor? = null

            for (descriptor in effects) {
                val uuid = descriptor.uuid?.toString()?.lowercase() ?: ""
                val typeUuid = descriptor.type?.toString()?.lowercase() ?: ""
                val name = descriptor.name?.lowercase() ?: ""

                var isMatch = false
                // Check known UUIDs first (most reliable)
                if (uuid == DOLBY_EFFECT_TYPE_UUID || uuid == DOLBY_DAX_EFFECT_UUID ||
                    typeUuid == DOLBY_EFFECT_TYPE_UUID || typeUuid == DOLBY_DAX_EFFECT_UUID
                ) {
                    found = true
                    isMatch = true
                    // DAX effect UUID implies Atmos-capable processing
                    if (uuid == DOLBY_DAX_EFFECT_UUID || typeUuid == DOLBY_DAX_EFFECT_UUID) {
                        isAtmos = true
                    }
                }

                // Fallback: case-insensitive name matching (best-effort)
                if (name.contains("dolby") || name.contains("dax")) {
                    found = true
                    isMatch = true
                    if (name.contains("atmos")) {
                        isAtmos = true
                    }
                }

                if (isMatch && matchedDescriptor == null) {
                    matchedDescriptor = descriptor
                }
            }

            Log.d(TAG, "  (b) Dolby effect: found=$found, atmos=$isAtmos")
            EffectResult(found, isAtmos, matchedDescriptor)
        } catch (e: Exception) {
            // SecurityException can occur on some OEM ROMs that restrict queryEffects()
            Log.w(TAG, "  (b) Dolby effect query failed", e)
            EffectResult(false, false, null)
        }
    }

    private fun detectDtsEffect(): Boolean {
        return try {
            val effects = AudioEffect.queryEffects() ?: emptyArray()
            for (descriptor in effects) {
                val name = descriptor.name?.lowercase() ?: ""
                if (name.contains("dts")) {
                    Log.d(TAG, "  (b) DTS effect: found ($name)")
                    return true
                }
            }
            Log.d(TAG, "  (b) DTS effect: not found")
            false
        } catch (e: Exception) {
            Log.w(TAG, "  (b) DTS effect query failed", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Method (c): OEM app package detection via PackageManager
    // CONFIDENCE: MEDIUM
    //
    // Checks if known Dolby system app packages are installed. These are
    // pre-installed by OEMs on Dolby-licensed devices and cannot be installed
    // from Play Store on non-licensed hardware.
    //
    // Caveat: Package might be disabled by user. We still count it as the
    // hardware is Dolby-capable regardless of whether the user disabled the app.
    // ─────────────────────────────────────────────────────────────────
    private data class AppResult(val found: Boolean, val isAtmos: Boolean)

    private fun detectDolbyApp(): AppResult {
        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            var found = false
            var isAtmos = false

            for (packageInfo in packages) {
                val pkgName = packageInfo.packageName.lowercase()
                if (pkgName.contains("dolby")) {
                    found = true
                    if (pkgName.contains("atmos") || pkgName.contains("dax") || pkgName.contains("dap")) {
                        isAtmos = true
                    }
                    Log.d(TAG, "  (c) Dolby package found: $pkgName (atmos=$isAtmos)")
                }
            }

            if (!found) {
                Log.d(TAG, "  (c) No Dolby packages found")
            }

            AppResult(found, isAtmos)
        } catch (e: Exception) {
            Log.w(TAG, "  (c) Dolby package query failed", e)
            AppResult(false, false)
        }
    }

    private fun detectDtsApp(): Boolean {
        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            var found = false
            for (packageInfo in packages) {
                val pkgName = packageInfo.packageName.lowercase()
                if (pkgName.contains("dts") && (
                    pkgName.contains("sound") ||
                    pkgName.contains("audio") ||
                    pkgName.contains("effect") ||
                    pkgName.contains("dtsx") ||
                    pkgName.contains("ultra") ||
                    pkgName.contains("headphone") ||
                    pkgName.contains("service") ||
                    pkgName.contains("dsp") ||
                    pkgName.contains("control") ||
                    pkgName.contains("system") ||
                    pkgName.contains("hardware") ||
                    pkgName.contains("software")
                )) {
                    found = true
                    Log.d(TAG, "  (c) DTS package found: $pkgName")
                }
            }
            if (!found) {
                Log.d(TAG, "  (c) No DTS packages found")
            }
            found
        } catch (e: Exception) {
            Log.w(TAG, "  (c) DTS package query failed", e)
            false
        }
    }


    // ─────────────────────────────────────────────────────────────────
    // Hi-Res Audio detection
    // CONFIDENCE: HIGH (for output device capability)
    //
    // Checks if ANY output device supports:
    //   - Sample rate ≥ 96,000 Hz (CD quality is 44,100 Hz)
    //   - AND high bit-depth PCM (24-bit or 32-bit or float)
    //
    // This indicates the hardware DAC can process hi-res streams.
    // Note: This does NOT mean the current audio stream IS hi-res —
    // it only means the output chain supports it.
    // ─────────────────────────────────────────────────────────────────
    private fun detectHiResCapability(): Boolean {
        return try {
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val hiResEncodings = setOf(
                AudioFormat.ENCODING_PCM_24BIT_PACKED, // API 31+, but value exists since 21
                AudioFormat.ENCODING_PCM_32BIT,        // API 31+
                AudioFormat.ENCODING_PCM_FLOAT,        // API 21+
            )

            for (device in outputDevices) {
                val sampleRates = device.sampleRates
                val encodings = device.encodings

                // Empty arrays mean "device supports any" — which is common for built-in speaker.
                // We only flag hi-res if we get explicit high sample rate + encoding evidence.
                if (sampleRates.isEmpty() || encodings.isEmpty()) continue

                val hasHighSampleRate = sampleRates.any { it >= 96_000 }
                val hasHighBitDepth = encodings.any { it in hiResEncodings }

                if (hasHighSampleRate && hasHighBitDepth) {
                    Log.d(TAG, "  Hi-Res: capable (device=${device.productName}, " +
                            "rates=${sampleRates.maxOrNull()}, has24bit=$hasHighBitDepth)")
                    return true
                }
            }

            Log.d(TAG, "  Hi-Res: not detected on any output device")
            false
        } catch (e: Exception) {
            Log.w(TAG, "  Hi-Res detection failed", e)
            false
        }
    }

    private fun detectExternalDac(): Boolean {
        return try {
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            outputDevices.any { device ->
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }
        } catch (e: Exception) {
            Log.w(TAG, "  External DAC check failed", e)
            false
        }
    }

    private data class SpatializerResult(
        val isAvailable: Boolean = false,
        val isEnabled: Boolean = false,
        val immersiveLevel: Int = 0,
        /** Human-readable immersive audio level name */
        val immersiveLevelName: String = "",
        /** Whether a representative stereo/Opus stream can be spatialized on this device */
        val canBeSpatializedStereo: Boolean = false,
    )

    @Suppress("NewApi") // All usages are gated by SDK >= 32 check above
    private fun detectSpatializerSupport(): SpatializerResult {
        if (Build.VERSION.SDK_INT < 32) return SpatializerResult()
        return try {
            val spatializer = audioManager.spatializer
            val isAvail = spatializer.isAvailable
            val isEnabled = spatializer.isEnabled
            val level = spatializer.immersiveAudioLevel

            // Human-readable immersive level — constants defined in Spatializer (API 32)
            // Note: SPATIALIZER_IMMERSIVE_LEVEL_OVER_HEADSET (= 2) was added in API 34,
            // so we use the raw int value to maintain API 32 compatibility.
            val levelName = when (level) {
                Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE        -> "None"         // = 0
                Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL -> "Multichannel" // = 1
                2                                                   -> "Over-headset object audio" // LEVEL_OVER_HEADSET (API 34)
                else                                                -> "Level $level"
            }

            // canBeSpatialized() check with a representative stereo PCM format
            // (Opus/AAC streams are decoded to PCM before the spatializer sees them)
            val canSpat = if (isAvail) {
                try {
                    val testAttrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    val testFormat = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                    spatializer.canBeSpatialized(testAttrs, testFormat)
                } catch (e: Exception) {
                    Log.w(TAG, "  (spatializer) canBeSpatialized() failed", e)
                    false
                }
            } else false

            Log.d(TAG, "  (spatializer) available=$isAvail, enabled=$isEnabled, " +
                    "level=$levelName, canSpatializeStereo=$canSpat")
            SpatializerResult(
                isAvailable = isAvail,
                isEnabled = isEnabled,
                immersiveLevel = level,
                immersiveLevelName = levelName,
                canBeSpatializedStereo = canSpat,
            )
        } catch (e: Exception) {
            Log.w(TAG, "  (spatializer) check failed", e)
            SpatializerResult()
        }
    }

    private fun registerSpatializerListener() {
        if (Build.VERSION.SDK_INT >= 32) {
            try {
                val spatializer = audioManager.spatializer
                val listener = object : Spatializer.OnSpatializerStateChangedListener {
                    override fun onSpatializerEnabledChanged(spatializer: Spatializer, enabled: Boolean) {
                        Log.d(TAG, "Spatializer enabled changed: $enabled")
                        refresh()
                    }

                    override fun onSpatializerAvailableChanged(spatializer: Spatializer, available: Boolean) {
                        Log.d(TAG, "Spatializer availability changed: $available")
                        refresh()
                    }
                }
                spatializer.addOnSpatializerStateChangedListener(
                    { command -> Handler(Looper.getMainLooper()).post(command) },
                    listener
                )
                spatializerListener = listener
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register spatializer listener", e)
            }
        }
    }

    private fun detectDolbyCodecSupport(): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val atmosMimes = setOf("audio/eac3", "audio/eac3-joc", "audio/ac4")
            val hasCodec = codecList.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.lowercase() in atmosMimes }
            }
            Log.d(TAG, "  (codec) Dolby codec support: $hasCodec")
            hasCodec
        } catch (e: Exception) {
            Log.w(TAG, "  (codec) Dolby codec check failed", e)
            false
        }
    }

}
