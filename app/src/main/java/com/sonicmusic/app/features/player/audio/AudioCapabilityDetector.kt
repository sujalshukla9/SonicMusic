package com.sonicmusic.app.features.player.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
 * Detects device-level audio enhancement capabilities (DTS, Hi-Res Audio, Spatializer).
 *
 * ## Detection Confidence Levels
 *
 * **HIGH confidence:**
 * - [AudioDeviceInfo.getEncodings] reporting DTS decoder support (method a)
 * - [AudioEffect.queryEffects] finding DTS DSP effects (method b)
 *
 * **MEDIUM confidence:**
 * - System package manager finding OEM DTS apps (method c)
 *
 * ## Important Caveats
 *
 * Android's audio pipeline applies OEM DSP effects (Samsung Sound Alive, etc.)
 * **after** the app's audio output reaches the HAL. There is NO public API to query whether
 * "OEM sound effects are currently toggled ON in system settings." The best we can do is detect
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

        // ── Multichannel Spatialization ────────────────────────────
        /** True when device can spatialize 5.1 surround content */
        val canBeSpatializedSurround51: Boolean = false,
        /** True when device can spatialize 7.1 surround content */
        val canBeSpatializedSurround71: Boolean = false,

        // ── Head Tracking ─────────────────────────────────────────
        /** True when the Spatializer supports head-tracking on this device (API 32+) */
        val headTrackingAvailable: Boolean = false,
        /** Current head-tracking mode name (e.g. "Relative World", "Relative Screen") */
        val headTrackingModeName: String = "",

        // ── Output Route ──────────────────────────────────────────
        /** Name of the currently active audio output route (e.g. "Pixel Buds Pro") */
        val currentOutputRouteName: String = "Speaker",

        /**
         * True when the OS Spatializer pipeline is actively processing audio.
         * When true, the in-app Virtualizer should defer to avoid double spatial processing.
         */
        val osSpatialProcessingActive: Boolean = false,

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
        /**
         * True if ANY capability badge should be shown.
         * Spatial Audio is included only when the device reports availability (API 32+).
         */
        val hasAnyCapability: Boolean
            get() = dtsLabel != null || hiResLabel != null ||
                    outputLabel != null || spatialLabel != null

        /** Ordered list of badge labels to display (Spatial Audio last, after hardware badges) */
        val badgeLabels: List<String>
            get() = listOfNotNull(dtsLabel, hiResLabel, outputLabel, spatialLabel)

        /** Best spatialization format supported (for display) */
        val bestSpatialFormat: String
            get() = when {
                canBeSpatializedSurround71 -> "7.1 Surround"
                canBeSpatializedSurround51 -> "5.1 Surround"
                canBeSpatializedStereoOpus -> "Stereo"
                else -> "None"
            }
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
        val spatializerResult = detectSpatializerSupport()

        val dtsDecoder = detectDtsDecoderSupport()
        val dtsEffect = detectDtsEffect()
        val dtsApp = detectDtsApp()
        val hasDts = dtsDecoder || dtsEffect || dtsApp

        val hiRes = detectHiResCapability()
        val externalDac = detectExternalDac()
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

        // ── Output route name ─────────────────────────────────────
        val outputRouteName = detectCurrentOutputRouteName()

        return AudioCapability(
            hasSpatializer = spatializerResult.isAvailable,
            spatializerEnabled = spatializerResult.isEnabled,
            spatialLabel = spatialLabel,
            spatializerActive = spatializerActive,
            spatializerLevelName = spatializerResult.immersiveLevelName,
            canBeSpatializedStereoOpus = spatializerResult.canBeSpatializedStereo,
            canBeSpatializedSurround51 = spatializerResult.canBeSpatializedSurround51,
            canBeSpatializedSurround71 = spatializerResult.canBeSpatializedSurround71,
            headTrackingAvailable = spatializerResult.headTrackingAvailable,
            headTrackingModeName = spatializerResult.headTrackingModeName,
            currentOutputRouteName = outputRouteName,
            osSpatialProcessingActive = spatializerActive,
            hasDts = hasDts,
            dtsLabel = dtsLabel,
            isHiResCapable = hiRes,
            hiResLabel = hiResLabel,
            hasExternalDac = externalDac,
            outputLabel = outputLabel,
        )
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
    // Checks if known DTS system app packages are installed. These are
    // pre-installed by OEMs on DTS-licensed devices and cannot be installed
    // from Play Store on non-licensed hardware.
    //
    // Caveat: Package might be disabled by user. We still count it as the
    // hardware is DTS-capable regardless of whether the user disabled the app.
    // ─────────────────────────────────────────────────────────────────

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
        /** Whether 5.1 surround content can be spatialized */
        val canBeSpatializedSurround51: Boolean = false,
        /** Whether 7.1 surround content can be spatialized */
        val canBeSpatializedSurround71: Boolean = false,
        /** Whether the device supports head tracking for spatial audio */
        val headTrackingAvailable: Boolean = false,
        /** Human-readable head tracking mode name */
        val headTrackingModeName: String = "",
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

            // Build shared AudioAttributes for spatialization tests
            val testAttrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            // ── Stereo spatialization test ──────────────────────────
            val canSpatStereo = if (isAvail) {
                try {
                    val stereoFormat = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                    spatializer.canBeSpatialized(testAttrs, stereoFormat)
                } catch (e: Exception) {
                    Log.w(TAG, "  (spatializer) canBeSpatialized(stereo) failed", e)
                    false
                }
            } else false

            // ── 5.1 Surround spatialization test ───────────────────
            val canSpat51 = if (isAvail) {
                try {
                    val surround51Format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                        .build()
                    spatializer.canBeSpatialized(testAttrs, surround51Format)
                } catch (e: Exception) {
                    Log.w(TAG, "  (spatializer) canBeSpatialized(5.1) failed", e)
                    false
                }
            } else false

            // ── 7.1 Surround spatialization test ───────────────────
            val canSpat71 = if (isAvail) {
                try {
                    val surround71Format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)
                        .build()
                    spatializer.canBeSpatialized(testAttrs, surround71Format)
                } catch (e: Exception) {
                    Log.w(TAG, "  (spatializer) canBeSpatialized(7.1) failed", e)
                    false
                }
            } else false

            // ── Head Tracking detection ────────────────────────────
            val headTrackingResult = detectHeadTracking(spatializer)

            Log.d(TAG, "  (spatializer) available=$isAvail, enabled=$isEnabled, " +
                    "level=$levelName, canSpatStereo=$canSpatStereo, " +
                    "canSpat51=$canSpat51, canSpat71=$canSpat71, " +
                    "headTracking=${headTrackingResult.first}")
            SpatializerResult(
                isAvailable = isAvail,
                isEnabled = isEnabled,
                immersiveLevel = level,
                immersiveLevelName = levelName,
                canBeSpatializedStereo = canSpatStereo,
                canBeSpatializedSurround51 = canSpat51,
                canBeSpatializedSurround71 = canSpat71,
                headTrackingAvailable = headTrackingResult.first,
                headTrackingModeName = headTrackingResult.second,
            )
        } catch (e: Exception) {
            Log.w(TAG, "  (spatializer) check failed", e)
            SpatializerResult()
        }
    }

    /**
     * Detect head tracking capability from the Spatializer.
     * Returns Pair(isAvailable, modeName).
     * Head tracking modes were added in API 33.
     */
    @Suppress("NewApi")
    private fun detectHeadTracking(spatializer: Spatializer): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < 33) return Pair(false, "")
        return try {
            // Spatializer.isHeadTrackerAvailable() — API 33+
            val headTrackerAvailable = try {
                spatializer.isHeadTrackerAvailable
            } catch (e: Exception) {
                Log.w(TAG, "  (headTracking) isHeadTrackerAvailable() failed", e)
                false
            }

            val modeName = if (headTrackerAvailable) {
                val mode = try {
                    val method = spatializer.javaClass.getMethod("getHeadTrackingMode")
                    method.invoke(spatializer) as? Int
                } catch (_: Exception) {
                    null
                }
                when (mode) {
                    0 -> "Disabled"
                    1 -> "Relative World"
                    2 -> "Relative Screen"
                    else -> "Supported"
                }
            } else ""

            Log.d(TAG, "  (headTracking) available=$headTrackerAvailable, mode=$modeName")
            Pair(headTrackerAvailable, modeName)
        } catch (e: Exception) {
            Log.w(TAG, "  (headTracking) detection failed", e)
            Pair(false, "")
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

                // Register head tracking listener (API 33+)
                registerHeadTrackingListener(spatializer)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register spatializer listener", e)
            }
        }
    }

    private var headTrackingListener: Any? = null

    @Suppress("NewApi")
    private fun registerHeadTrackingListener(spatializer: Spatializer) {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            val htListener = object : Spatializer.OnHeadTrackerAvailableListener {
                override fun onHeadTrackerAvailableChanged(
                    spatializer: Spatializer,
                    available: Boolean
                ) {
                    Log.d(TAG, "Head tracker availability changed: $available")
                    refresh()
                }
            }
            spatializer.addOnHeadTrackerAvailableListener(
                { command -> Handler(Looper.getMainLooper()).post(command) },
                htListener
            )
            headTrackingListener = htListener
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register head tracking listener", e)
        }
    }

    /**
     * Detect the name of the currently active audio output device.
     * Used to show contextual info (e.g. "Pixel Buds Pro", "Galaxy Buds2 Pro").
     */
    private fun detectCurrentOutputRouteName(): String {
        return try {
            val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            // Prioritize: Bluetooth > USB > Wired > Built-in
            val prioritized = outputDevices.firstOrNull { d ->
                d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: outputDevices.firstOrNull { d ->
                d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                d.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: outputDevices.firstOrNull { d ->
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            } ?: outputDevices.firstOrNull { d ->
                d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }

            val name = prioritized?.productName?.toString()?.takeIf { it.isNotBlank() }
                ?: when (prioritized?.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                    else -> "Speaker"
                }
            name
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect output route name", e)
            "Speaker"
        }
    }

}
