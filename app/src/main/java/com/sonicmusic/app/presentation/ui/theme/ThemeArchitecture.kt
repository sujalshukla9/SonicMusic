package com.sonicmusic.app.presentation.ui.theme

/**
 * Sonic Music Theme Architecture & Best Practices
 *
 * This document outlines the best practices for using Material 3 and dynamic theming
 * in Compose to avoid common pitfalls like recomposition lag and UI flickering.
 *
 * 1. Single Source of Truth
 * Always use `MaterialTheme.colorScheme` and `MaterialTheme.typography` to access styles.
 * Never hardcode colors in individual components.
 * Example: `modifier.background(MaterialTheme.colorScheme.surface)`
 *
 * 2. Optimized Theme Switching
 * - Use `animated()` on the `ColorScheme` to ensure fluid transitions when the theme changes (e.g., when a song changes and dynamic colors adapt).
 * - The animation duration and easing are defined in `AnimatedColorScheme.kt`.
 * - Theme transitions happen smoothly without causing jarring UI flickers because we interpolate each color explicitly.
 *
 * 3. Avoiding Unnecessary Recompositions
 * - Ensure that variables passed to `remember` in `SonicMusicTheme` (e.g., `seedColor`, `isDark`, `themeMode`) are derived correctly and only change when necessary.
 * - Avoid computing colors or passing inline functions directly inside Composables that are invoked frequently.
 * - By caching the `AnimatedColorScheme` state, we prevent the entire view tree from recomposing every frame of the theme animation.
 *
 * 4. Stable Parameters
 * Pass stable parameters to components (e.g., primitives, data classes with `val`, or `@Immutable` annotated classes).
 * If a parameter is unstable, Compose will unnecessarily recompose the component whenever its parent recomposes.
 *
 * 5. Material 3 Surface & Containers
 * M3 introduces a sophisticated elevation and surface container system. Use it appropriately:
 * - `surface`: Background of the app (scaffolding).
 * - `surfaceContainerLowest` to `surfaceContainerHighest`: Use for cards, dialogs, and elevated sheets depending on their z-index emphasis.
 *
 * 6. Dynamic Theming on Android < 12
 * Material You (`dynamicLightColorScheme` / `dynamicDarkColorScheme`) is only available on Android 12+.
 * For older devices, the system falls back to `LightColorScheme` and `DarkColorScheme` defined in `Theme.kt`, ensuring a consistent experience.
 *
 * 7. Edge-to-Edge System Bars
 * `Theme.kt` applies `WindowCompat.getInsetsController` to automatically adjust the status and navigation bar icon colors (light or dark) based on the background `surface` luminance.
 */
