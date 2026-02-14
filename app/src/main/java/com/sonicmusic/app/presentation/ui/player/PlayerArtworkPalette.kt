package com.sonicmusic.app.presentation.ui.player

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.sonicmusic.app.data.util.ThumbnailUrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import kotlin.math.max

data class PlayerArtworkPalette(
    val colorScheme: ColorScheme,
    val seedColor: Color,
    val container: Color,
    val containerSoft: Color,
    val accent: Color,
    val onAccent: Color,
    val dominantColors: List<Color> = emptyList()
)

private const val EXTRACTION_SIZE = 128
private const val PALETTE_CACHE_SIZE = 80
private const val MIN_TEXT_CONTRAST = 4.5

private class PlayerPaletteLruCache(private val maxSize: Int) {
    private val lock = Any()
    private val map = object : LinkedHashMap<String, PlayerArtworkPalette>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, PlayerArtworkPalette>
        ): Boolean = size > maxSize
    }

    fun get(key: String): PlayerArtworkPalette? = synchronized(lock) { map[key] }

    fun put(key: String, value: PlayerArtworkPalette) {
        synchronized(lock) { map[key] = value }
    }
}

private val paletteCache = PlayerPaletteLruCache(PALETTE_CACHE_SIZE)

@Composable
fun rememberPlayerArtworkPalette(
    artworkUrl: String?,
    fallbackColorScheme: ColorScheme = MaterialTheme.colorScheme,
    enabled: Boolean = true,
    intensity: Float = 1f
): PlayerArtworkPalette {
    val context = LocalContext.current
    val baseColorScheme = fallbackColorScheme
    val imageLoader = remember(context) {
        ImageLoader.Builder(context).build()
    }
    val isDarkTheme = baseColorScheme.surface.luminance() < 0.5f
    val normalizedIntensity = intensity.coerceIn(0f, 1f)

    val fallbackPalette = remember(baseColorScheme) {
        fallbackPalette(baseColorScheme)
    }
    if (!enabled || normalizedIntensity <= 0.001f) return fallbackPalette

    val originalUrl = remember(artworkUrl) {
        artworkUrl?.trim()?.takeIf { it.isNotEmpty() }
    }
    val candidateUrls = remember(originalUrl) {
        ThumbnailUrlUtils.buildCandidates(originalUrl)
    }

    val paletteState by produceState(
        initialValue = fallbackPalette,
        key1 = candidateUrls,
        key2 = isDarkTheme,
        key3 = fallbackPalette
    ) {
        value = loadOrExtractPalette(
            context = context,
            imageLoader = imageLoader,
            candidateUrls = candidateUrls,
            fallback = fallbackPalette,
            isDarkTheme = isDarkTheme
        )
    }

    return remember(paletteState, fallbackPalette, normalizedIntensity) {
        paletteState.withIntensity(
            fallback = fallbackPalette,
            intensity = normalizedIntensity
        )
    }
}

@Composable
fun PreloadPlayerArtworkPalette(
    artworkUrl: String?,
    enabled: Boolean = true,
    intensity: Float = 1f
) {
    val context = LocalContext.current
    val baseColorScheme = MaterialTheme.colorScheme
    val imageLoader = remember(context) {
        ImageLoader.Builder(context).build()
    }
    val isDarkTheme = baseColorScheme.surface.luminance() < 0.5f
    val normalizedIntensity = intensity.coerceIn(0f, 1f)
    val fallbackPalette = remember(baseColorScheme) {
        fallbackPalette(baseColorScheme)
    }
    if (!enabled || normalizedIntensity <= 0.001f) return
    val originalUrl = remember(artworkUrl) {
        artworkUrl?.trim()?.takeIf { it.isNotEmpty() }
    }
    val candidateUrls = remember(originalUrl) {
        ThumbnailUrlUtils.buildCandidates(originalUrl)
    }

    LaunchedEffect(candidateUrls, isDarkTheme, fallbackPalette) {
        loadOrExtractPalette(
            context = context,
            imageLoader = imageLoader,
            candidateUrls = candidateUrls,
            fallback = fallbackPalette,
            isDarkTheme = isDarkTheme
        )
    }
}

private suspend fun loadOrExtractPalette(
    context: Context,
    imageLoader: ImageLoader,
    candidateUrls: List<String>,
    fallback: PlayerArtworkPalette,
    isDarkTheme: Boolean
): PlayerArtworkPalette {
    if (candidateUrls.isEmpty()) return fallback

    candidateUrls.forEach { url ->
        val cacheKey = "$url|$isDarkTheme"
        paletteCache.get(cacheKey)?.let { return it }
    }

    candidateUrls.forEach { url ->
        val extracted = extractPaletteFromUrl(
            context = context,
            imageLoader = imageLoader,
            url = url,
            fallback = fallback,
            isDarkTheme = isDarkTheme
        ) ?: return@forEach

        // Cache successful extraction for all URL candidates of this artwork.
        candidateUrls.forEach { candidate ->
            paletteCache.put("$candidate|$isDarkTheme", extracted)
        }
        return extracted
    }

    return fallback
}

private suspend fun extractPaletteFromUrl(
    context: Context,
    imageLoader: ImageLoader,
    url: String,
    fallback: PlayerArtworkPalette,
    isDarkTheme: Boolean
): PlayerArtworkPalette? {
    return runCatching {
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .size(EXTRACTION_SIZE)
            .build()

        val result = withContext(Dispatchers.IO) {
            imageLoader.execute(request)
        }

        if (result is SuccessResult) {
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                ?: result.drawable.toBitmap(width = EXTRACTION_SIZE, height = EXTRACTION_SIZE)

            val palette = withContext(Dispatchers.Default) {
                Palette.Builder(bitmap)
                    .maximumColorCount(24)
                    .resizeBitmapArea(64 * 64)
                    .clearFilters()
                    .generate()
            }

            if (palette.swatches.isEmpty()) {
                null
            } else {
                palette.toPlayerArtworkPalette(
                    fallback = fallback,
                    isDarkTheme = isDarkTheme
                )
            }
        } else {
            null
        }
    }.getOrNull()
}

private fun fallbackPalette(colorScheme: ColorScheme): PlayerArtworkPalette {
    return PlayerArtworkPalette(
        colorScheme = colorScheme,
        seedColor = colorScheme.primary,
        container = colorScheme.surfaceContainerHigh,
        containerSoft = colorScheme.surfaceContainerLow,
        accent = colorScheme.primary,
        onAccent = colorScheme.onPrimary,
        dominantColors = listOf(
            colorScheme.primary,
            colorScheme.secondary,
            colorScheme.tertiary,
            colorScheme.surfaceVariant,
            colorScheme.surface
        )
    )
}

private fun Palette.toPlayerArtworkPalette(
    fallback: PlayerArtworkPalette,
    isDarkTheme: Boolean
): PlayerArtworkPalette {
    val seed = extractSeedColor(
        fallback = fallback.seedColor,
        isDarkTheme = isDarkTheme
    )
    val generatedColorScheme = generateArtworkColorScheme(
        seedColor = seed,
        fallback = fallback.colorScheme,
        isDarkTheme = isDarkTheme
    )

    val accent = generatedColorScheme.primary
    val onAccent = readableContentColor(
        background = accent,
        preferred = generatedColorScheme.onPrimary
    )

    val container = lerp(
        start = generatedColorScheme.surfaceContainerLow,
        stop = generatedColorScheme.primaryContainer,
        fraction = if (isDarkTheme) 0.30f else 0.22f
    )

    val containerSoft = lerp(
        start = generatedColorScheme.surfaceContainerLowest,
        stop = generatedColorScheme.surfaceContainerHigh,
        fraction = if (isDarkTheme) 0.44f else 0.30f
    )
    val dominantColors = extractDominantColors(seed)

    return PlayerArtworkPalette(
        colorScheme = generatedColorScheme,
        seedColor = seed,
        container = container,
        containerSoft = containerSoft,
        accent = accent,
        onAccent = onAccent,
        dominantColors = dominantColors
    )
}

private fun PlayerArtworkPalette.withIntensity(
    fallback: PlayerArtworkPalette,
    intensity: Float
): PlayerArtworkPalette {
    val t = intensity.coerceIn(0f, 1f)
    if (t <= 0.001f) return fallback
    if (t >= 0.999f) return this

    val blendedScheme = blendColorScheme(
        from = fallback.colorScheme,
        to = colorScheme,
        fraction = t
    )
    val blendedAccent = lerp(fallback.accent, accent, t)

    return PlayerArtworkPalette(
        colorScheme = blendedScheme,
        seedColor = lerp(fallback.seedColor, seedColor, t),
        container = lerp(fallback.container, container, t),
        containerSoft = lerp(fallback.containerSoft, containerSoft, t),
        accent = blendedAccent,
        onAccent = readableContentColor(
            background = blendedAccent,
            preferred = blendedScheme.onPrimary
        ),
        dominantColors = if (t >= 0.5f) dominantColors else fallback.dominantColors
    )
}

private fun blendColorScheme(
    from: ColorScheme,
    to: ColorScheme,
    fraction: Float
): ColorScheme {
    val t = fraction.coerceIn(0f, 1f)
    fun c(start: Color, end: Color): Color = lerp(start, end, t)

    return from.copy(
        primary = c(from.primary, to.primary),
        onPrimary = c(from.onPrimary, to.onPrimary),
        primaryContainer = c(from.primaryContainer, to.primaryContainer),
        onPrimaryContainer = c(from.onPrimaryContainer, to.onPrimaryContainer),
        inversePrimary = c(from.inversePrimary, to.inversePrimary),
        secondary = c(from.secondary, to.secondary),
        onSecondary = c(from.onSecondary, to.onSecondary),
        secondaryContainer = c(from.secondaryContainer, to.secondaryContainer),
        onSecondaryContainer = c(from.onSecondaryContainer, to.onSecondaryContainer),
        tertiary = c(from.tertiary, to.tertiary),
        onTertiary = c(from.onTertiary, to.onTertiary),
        tertiaryContainer = c(from.tertiaryContainer, to.tertiaryContainer),
        onTertiaryContainer = c(from.onTertiaryContainer, to.onTertiaryContainer),
        background = c(from.background, to.background),
        onBackground = c(from.onBackground, to.onBackground),
        surface = c(from.surface, to.surface),
        onSurface = c(from.onSurface, to.onSurface),
        surfaceVariant = c(from.surfaceVariant, to.surfaceVariant),
        onSurfaceVariant = c(from.onSurfaceVariant, to.onSurfaceVariant),
        surfaceTint = c(from.surfaceTint, to.surfaceTint),
        inverseSurface = c(from.inverseSurface, to.inverseSurface),
        inverseOnSurface = c(from.inverseOnSurface, to.inverseOnSurface),
        error = c(from.error, to.error),
        onError = c(from.onError, to.onError),
        errorContainer = c(from.errorContainer, to.errorContainer),
        onErrorContainer = c(from.onErrorContainer, to.onErrorContainer),
        outline = c(from.outline, to.outline),
        outlineVariant = c(from.outlineVariant, to.outlineVariant),
        scrim = c(from.scrim, to.scrim),
        surfaceBright = c(from.surfaceBright, to.surfaceBright),
        surfaceDim = c(from.surfaceDim, to.surfaceDim),
        surfaceContainer = c(from.surfaceContainer, to.surfaceContainer),
        surfaceContainerHigh = c(from.surfaceContainerHigh, to.surfaceContainerHigh),
        surfaceContainerHighest = c(from.surfaceContainerHighest, to.surfaceContainerHighest),
        surfaceContainerLow = c(from.surfaceContainerLow, to.surfaceContainerLow),
        surfaceContainerLowest = c(from.surfaceContainerLowest, to.surfaceContainerLowest)
    )
}

private fun Palette.extractSeedColor(
    fallback: Color,
    isDarkTheme: Boolean
): Color {
    val weighted = swatches
        .sortedByDescending { swatch ->
            val saturation = swatch.hsl.getOrNull(1) ?: 0f
            val luminanceBias = if (isDarkTheme) {
                0.7f + ((swatch.hsl.getOrNull(2) ?: 0f) * 0.6f)
            } else {
                0.8f + ((1f - (swatch.hsl.getOrNull(2) ?: 0f)) * 0.4f)
            }
            swatch.population * max(0.15f, saturation) * luminanceBias
        }
        .firstOrNull()
    return weighted?.let { Color(it.rgb) } ?: fallback
}

private fun Palette.extractDominantColors(fallback: Color): List<Color> {
    return swatches
        .sortedByDescending { it.population }
        .asSequence()
        .map { Color(it.rgb) }
        .distinctBy { it.toArgb() }
        .take(5)
        .toList()
        .ifEmpty { listOf(fallback) }
}

private data class ArtworkTonalPalettes(
    val primary: TonePalette,
    val secondary: TonePalette,
    val tertiary: TonePalette,
    val neutral: TonePalette,
    val neutralVariant: TonePalette
)

private class TonePalette(
    private val hue: Float,
    private val saturation: Float
) {
    fun tone(value: Int): Color {
        val tone = value.coerceIn(0, 100)
        val lightness = tone / 100f
        val adjustedSaturation = when {
            tone >= 95 -> saturation * 0.35f
            tone <= 10 -> saturation * 0.55f
            else -> saturation
        }.coerceIn(0f, 1f)
        return hslColor(hue = hue, saturation = adjustedSaturation, lightness = lightness)
    }
}

private fun generateArtworkColorScheme(
    seedColor: Color,
    fallback: ColorScheme,
    isDarkTheme: Boolean
): ColorScheme {
    val tones = generateTonalPalettes(seedColor)
    return if (isDarkTheme) {
        darkColorScheme(
            primary = tones.primary.tone(80),
            onPrimary = tones.primary.tone(20),
            primaryContainer = tones.primary.tone(30),
            onPrimaryContainer = tones.primary.tone(90),
            secondary = tones.secondary.tone(80),
            onSecondary = tones.secondary.tone(20),
            secondaryContainer = tones.secondary.tone(30),
            onSecondaryContainer = tones.secondary.tone(90),
            tertiary = tones.tertiary.tone(80),
            onTertiary = tones.tertiary.tone(20),
            tertiaryContainer = tones.tertiary.tone(30),
            onTertiaryContainer = tones.tertiary.tone(90),
            error = fallback.error,
            onError = fallback.onError,
            errorContainer = fallback.errorContainer,
            onErrorContainer = fallback.onErrorContainer,
            background = tones.neutral.tone(6),
            onBackground = tones.neutral.tone(90),
            surface = tones.neutral.tone(6),
            onSurface = tones.neutral.tone(90),
            surfaceVariant = tones.neutralVariant.tone(30),
            onSurfaceVariant = tones.neutralVariant.tone(80),
            outline = tones.neutralVariant.tone(60),
            outlineVariant = tones.neutralVariant.tone(30),
            scrim = Color.Black,
            inverseSurface = tones.neutral.tone(90),
            inverseOnSurface = tones.neutral.tone(20),
            inversePrimary = tones.primary.tone(40)
        )
    } else {
        lightColorScheme(
            primary = tones.primary.tone(40),
            onPrimary = tones.primary.tone(100),
            primaryContainer = tones.primary.tone(90),
            onPrimaryContainer = tones.primary.tone(10),
            secondary = tones.secondary.tone(40),
            onSecondary = tones.secondary.tone(100),
            secondaryContainer = tones.secondary.tone(90),
            onSecondaryContainer = tones.secondary.tone(10),
            tertiary = tones.tertiary.tone(40),
            onTertiary = tones.tertiary.tone(100),
            tertiaryContainer = tones.tertiary.tone(90),
            onTertiaryContainer = tones.tertiary.tone(10),
            error = fallback.error,
            onError = fallback.onError,
            errorContainer = fallback.errorContainer,
            onErrorContainer = fallback.onErrorContainer,
            background = tones.neutral.tone(98),
            onBackground = tones.neutral.tone(10),
            surface = tones.neutral.tone(98),
            onSurface = tones.neutral.tone(10),
            surfaceVariant = tones.neutralVariant.tone(90),
            onSurfaceVariant = tones.neutralVariant.tone(30),
            outline = tones.neutralVariant.tone(50),
            outlineVariant = tones.neutralVariant.tone(80),
            scrim = Color.Black,
            inverseSurface = tones.neutral.tone(20),
            inverseOnSurface = tones.neutral.tone(95),
            inversePrimary = tones.primary.tone(80)
        )
    }
}

private fun generateTonalPalettes(seedColor: Color): ArtworkTonalPalettes {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seedColor.toArgb(), hsl)
    val baseHue = hsl[0]
    val baseSaturation = hsl[1].coerceIn(0.18f, 0.9f)

    return ArtworkTonalPalettes(
        primary = TonePalette(
            hue = baseHue,
            saturation = baseSaturation.coerceAtLeast(0.28f)
        ),
        secondary = TonePalette(
            hue = rotateHue(baseHue, 26f),
            saturation = (baseSaturation * 0.56f).coerceIn(0.16f, 0.52f)
        ),
        tertiary = TonePalette(
            hue = rotateHue(baseHue, -28f),
            saturation = (baseSaturation * 0.70f).coerceIn(0.20f, 0.65f)
        ),
        neutral = TonePalette(
            hue = baseHue,
            saturation = (baseSaturation * 0.22f).coerceIn(0.08f, 0.30f)
        ),
        neutralVariant = TonePalette(
            hue = baseHue,
            saturation = (baseSaturation * 0.32f).coerceIn(0.12f, 0.40f)
        )
    )
}

private fun readableContentColor(background: Color, preferred: Color): Color {
    val preferredContrast = ColorUtils.calculateContrast(
        preferred.toArgb(),
        background.toArgb()
    )
    if (preferredContrast >= MIN_TEXT_CONTRAST) return preferred

    val whiteContrast = ColorUtils.calculateContrast(
        Color.White.toArgb(),
        background.toArgb()
    )
    val blackContrast = ColorUtils.calculateContrast(
        Color.Black.toArgb(),
        background.toArgb()
    )

    return if (whiteContrast >= blackContrast) Color.White else Color.Black
}

private fun rotateHue(baseHue: Float, amount: Float): Float {
    var hue = (baseHue + amount) % 360f
    if (hue < 0f) hue += 360f
    return hue
}

private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    return Color(
        ColorUtils.HSLToColor(
            floatArrayOf(
                hue,
                saturation.coerceIn(0f, 1f),
                lightness.coerceIn(0f, 1f)
            )
        )
    )
}
