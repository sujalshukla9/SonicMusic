# Sonic Music - Design System & Jetpack Compose UI Specifications

This document defines the high-fidelity Android UI/UX design specifications for **Sonic Music**. It has been curated to match Google's latest **Material 3 Expressive** design guidelines, native Android 16 system aesthetics, and **Material You (Dynamic Color)** principles.

---

## 1. Brand Identity & Personality

* **App Name**: Sonic Music
* **Personality**: Modern, immersive, energetic, elegant, fluid, and responsive.
* **Core Goal**: A seamless, high-fidelity music streaming experience that feels native to modern Pixel and Android 16 devices.
* **Visual Pillars**:
  1. **Dynamic Color Harmonization**: Seamless color transitions as album art updates, establishing ambient immersion.
  2. **Expressive Geometry**: High-curvature corners, shape morphing transitions, and spacious interactive containers.
  3. **Micro-interactions & Physics-Based Motion**: Swipe-to-expand transitions, bouncing progress bars, and rotating records that match actual track speed and state.

---

## 2. Core Design System & Tokens

### 2.1. Color Tokens (Base Palette)

Sonic Music uses a dynamic theme extraction system. Below is the static default fallback palette mapped to Material Design 3 tokens.

#### Light Theme Base Tokens
| M3 Token | Value (Hex) | Usage Description |
| :--- | :--- | :--- |
| `primary` | `#3059C5` | Primary actions, play button, active tab icons |
| `onPrimary` | `#FFFFFF` | Text/icon label on top of primary colors |
| `primaryContainer` | `#DCE1FF` | High-emphasis accent background cards |
| `onPrimaryContainer`| `#00174B` | High-emphasis card text/icons |
| `secondaryContainer`| `#DCE3F9` | Tab selection indicator background |
| `background` | `#FBF8FF` | Standard screen backing surface |
| `surfaceContainerLow`| `#F6F2FA` | Standard card background |
| `surfaceContainer` | `#F0ECF4` | Bottom navigation bar fill |
| `surfaceContainerHigh`| `#EAE7EF` | Settings grouping containers |

#### Dark Theme Base Tokens
| M3 Token | Value (Hex) | Usage Description |
| :--- | :--- | :--- |
| `primary` | `#B7C4FF` | Accent highlights, text links, active status |
| `onPrimary` | `#002B79` | Dark text on primary highlights |
| `primaryContainer` | `#0F43AB` | Accent background cards |
| `onPrimaryContainer`| `#DCE1FF` | Text on accent background cards |
| `secondaryContainer`| `#3D475F` | Active bottom nav pill background |
| `background` | `#111318` | Premium dark backdrop |
| `surfaceContainerLow`| `#1A1B20` | Secondary layout backings |
| `surfaceContainer` | `#1E1F25` | Bottom navigation bar, player surface |
| `surfaceContainerHigh`| `#282A2F` | Expressive cards, list grouping backgrounds |

> [!NOTE]
> **Dynamic Extraction Logic**: Utilizing the Android Palette API or a customized color extraction loop on the artwork's thumbnail bitmap:
> 1. Extract the dominant/vibrant seed color (fallback to `#3059C5`).
> 2. Generate a full tonally-harmonized `ColorScheme` using the HCT (Hue, Chroma, Tone) color space.
> 3. Interpolate (blend) between the base theme and the extracted scheme based on the user-selected **vibrancy strength** slider.

---

### 2.2. Typography Scale

Sonic Music adopts the **Material You Typography Scale** with custom weights matching the Brand voice. We leverage the `Outfit` or standard sans-serif system typeface.

| Compose Text Style | Weight | Size (sp) | Line Height (sp) | Letter Spacing (sp) |
| :--- | :--- | :--- | :--- | :--- |
| `displayLarge` | `Normal` | `57` | `64` | `-0.25` |
| `displayMedium` | `Normal` | `45` | `52` | `0` |
| `displaySmall` | `Normal` | `36` | `44` | `0` |
| `headlineLarge` | `SemiBold` | `32` | `40` | `-0.2` |
| `headlineMedium` | `SemiBold` | `28` | `36` | `-0.1` |
| `headlineSmall` | `SemiBold` | `24` | `32` | `0` |
| `titleLarge` | `SemiBold` | `22` | `28` | `0` |
| `titleMedium` | `Medium` | `16` | `24` | `0.15` |
| `titleSmall` | `Medium` | `14` | `20` | `0.1` |
| `bodyLarge` | `Normal` | `16` | `24` | `0.2` |
| `bodyMedium` | `Normal` | `14` | `20` | `0.1` |
| `bodySmall` | `Normal` | `12` | `16` | `0.15` |
| `labelLarge` | `Medium` | `14` | `20` | `0.1` |
| `labelMedium` | `Medium` | `12` | `16` | `0.3` |
| `labelSmall` | `Medium` | `11` | `16` | `0.3` |

---

### 2.3. Spacing & Layout System

We employ an **8dp baseline grid** with **4dp subdivisions** for micro-spacing.

* **Grid Padding (Compact Phones)**: `16.dp` edge margin.
* **Grid Padding (Tablets/Foldables)**: `24.dp` edge margin.
* **Card Inter-spacing**: `12.dp` or `16.dp` based on container visual weight.
* **List Row Spacing**: `8.dp` vertical gap.
* **Section Padding**: `24.dp` spacing between headers.

### 2.4. Shapes & Roundness System

In Material 3 Expressive, shapes play a key role in representing interactive hierarchies:
* **Circle (`CircleShape`)**: Circular buttons (Play/Pause FAB), artist thumbnails, pill badges.
* **Extra Large (`RoundedCornerShape(28.dp)`)**: Bottom sheets, onboarding hero containers, dialogue windows.
* **Large (`RoundedCornerShape(24.dp)`)**: Featured section cards, playlists row frames, settings group panel containers.
* **Medium (`RoundedCornerShape(16.dp)`)**: Album artwork thumbnails, regular list card items, search bar inputs.
* **Small/Extra Small (`RoundedCornerShape(12.dp / 8.dp)`)**: Quick action chips, badges, overlay tools.

---

## 3. Reusable Component Specs

### 3.1. Dynamic Color Card (Expressive Container)
```kotlin
@Composable
fun ExpressiveCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: CornerBasedShape = MaterialTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}
```

### 3.2. Animated Rotating Album Artwork
The album artwork on the Full Player utilizes continuous rotation when playing, with smooth speed transitions.
* **Scale Behavior**: Standard state `1.0f`. Pressed/paused shrinks slightly to `0.95f` using `animateFloatAsState`.
* **Rotation Rotation Speed**: Full rotation every 20 seconds.
```kotlin
val infiniteTransition = rememberInfiniteTransition(label = "ArtworkRotation")
val rotationAngle by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
        animation = tween(durationMillis = 20000, easing = LinearEasing),
        repeatMode = RepeatMode.Restart
    ),
    label = "rotation"
)
```

---

## 4. Screen Specifications

Here are the design mockups, responsive guidelines, and Compose UI specifications for the 15 required screens.

---

### Screen 1: Splash Screen
* **Layout Structure**: Single full-screen container with a rich ambient gradient.
* **Key Visuals**:
  * Sonic Music logo centered with custom Vector path morphing.
  * Animated audio waveform centered below the logo. Waveform has 7 vertical bars that scale up/down asynchronously using Compose `infiniteTransition`.
* **Animations**:
  * Logo enters with a scale-up (`0.7f` to `1.0f`) and alpha fade-in over 800ms (`OvershootInterpolator` feel).
  * Smooth background color transition blending into the onboarding/home screen theme.

---

### Screen 2: Onboarding Screen
* **Layout Structure**: Vertical Column containing:
  * Horizontal `HorizontalPager` displaying 3 slides.
  * Bottom row containing a custom pager dot indicator, a "Sign In" button, and a "Continue as Guest" filled button.
* **Content per Slide**:
  1. *Welcome to Sonic*: High-fidelity vector artwork illustrating abstract audio waves. Expressive heading: "Your Music, Reimagined."
  2. *Smart Search*: Graphic showing genre categorization bubbles. Expressive heading: "Discover the Vibe."
  3. *Offline Autopilot*: Illustration showing downloads with progress circles. Expressive heading: "Music Anywhere."
* **Compose State Specification**:
  ```kotlin
  val pagerState = rememberPagerState(pageCount = { 3 })
  ```
  * Buttons change primary colors dynamically based on the current page index.

---

### Screen 3: Home Screen
* **Layout Structure**: Scrollable `LazyColumn` containing:
  * Greeting Header with Profile Icon (top right) and Settings shortcut.
  * **Recently Played**: Horizontal scroll of medium square cards (`120.dp` width).
  * **Quick Picks**: 2x3 grid of track list items with quick play icons.
  * **Trending Songs**: Large horizontal cards with thumbnail overlay and track metadata.
  * **Recommended For You**: Grid of album cards.
  * **New Releases**: Horizontal carousel with shape-morphing thumbnails.
  * **Mood Categories**: Flexbox FlowRow with dynamic chips (e.g. "Chill", "Workout", "Focus").
  * **Popular Artists**: Circle cards with artist portraits and "Follow" buttons.
  * **Continue Listening**: Wide-banner card overlay at the bottom.

---

### Screen 4: Search Screen
* **Layout Structure**: 
  * Sticky top Search Bar utilizing Material 3 search-style container with high rounding (`28.dp`).
  * Scrolling area with:
    * **Recent Searches**: List with remove icon buttons.
    * **Trending Searches**: Chips layout.
    * **Genre Grid**: Two-column responsive card layouts with dynamic color tiles representing different genres (e.g., Pop, Hip Hop, Jazz).
    * **Artist Categories**: List of categories with thumbnail lists.
  * Sticky microphone button next to the search bar for Voice Search.

---

### Screen 5: Library Screen
* **Layout Structure**:
  * Collapsing Top App Bar.
  * Sticky tabs header for: Songs, Albums, Artists, Playlists, Downloads.
  * Horizontal list of Hero Cards at the top ("Liked Songs" and "Recently Played").
  * List items below featuring swipe actions (Swipe-to-download, Swipe-to-delete).
  * Multi-select toolbar appears when long-pressing any list item.

---

### Screen 6: Album Details Screen
* **Layout Structure**: Immersive collapsing header screen.
  * Sticky header: Album artwork collapses from full size (`280.dp`) to small title bar icon as the user scrolls up.
  * Extraction of primary vibrant color from album artwork to tint background gradient.
  * Interactive play and shuffle button row located directly underneath the header metadata.
  * Scrollable tracklist containing duration details, lyrics indicator, and item options.

---

### Screen 7: Artist Profile Screen
* **Layout Structure**:
  * High-fidelity hero banner containing the artist profile image.
  * Parallax scroll effect: Artist profile image scales and moves under the app bar.
  * Floating card indicating Monthly Listeners (e.g., "34,892,102 monthly listeners").
  * Horizontal carousel of albums, single tracks, and a list of related artists at the bottom.

---

### Screen 8: Playlist Details Screen
* **Layout Structure**:
  * Large Playlist cover art (`160.dp` x `160.dp`) with glassmorphism blur background.
  * Metadata row showing Creator name, total tracks, duration, and download state.
  * Action row with Play, Shuffle, Download toggle, and Share buttons.
  * Draggable track list items with custom handles for reordering.

---

### Screen 9: Full Player Screen
* **Layout Structure**: Single page modal overlay.
  * Background: Ultra-blurred version of the current song thumbnail (25% opacity) blending into black.
  * Large center artwork with rounding (`28.dp`) and scale animation based on play/pause status.
  * **Controls**:
    * Floating playback control bar containing play/pause, seek track buttons, and progress bar.
    * Slider: Custom slider showing playback time elapsed vs remaining.
    * Footer buttons: Lyrics panel trigger, Favorite (heart) button, Queue selection, Share sheet launcher.

---

### Screen 10: Mini Player Screen
* **Layout Structure**: Floating bottom container (`64.dp` height) that stays persistent above the bottom navigation bar.
  * Round artwork thumbnail.
  * Single line text for Track Title and Artist Name.
  * Core inline actions: Play/Pause button, skip next button.
  * Gesture handling: Horizontal swipe skips track, vertical swipe expands into the Full Player.

---

### Screen 11: Lyrics Screen
* **Layout Structure**: Secondary modal overlays or overlay on the Full Player.
  * Vertically scrollable line-by-line lyrics.
  * Karaoke Highlight style: Current line is highlighted in large bold white text (`24.sp`), while preceding and succeeding lines are translucent (`35%` opacity).
  * Auto-scrolling: Syncs with media playback timestamp. Clicking any lyric line jumps track playback directly to that position.

---

### Screen 12: Queue Screen
* **Layout Structure**: List overlay sheet.
  * "Now Playing" highlight card.
  * "Next Up" listing.
  * Features:
    * Drag handles on the right side of list items for quick reordering.
    * Swipe actions to dismiss/remove songs from the current queue.

---

### Screen 13: Download Manager
* **Layout Structure**: Split view page:
  * Top Card: Storage overview showing device storage bar chart (Sonic Music files vs other files).
  * Bottom Scroll list: Active downloads with real-time speed indicators, completed files, and pause/resume button arrays.

---

### Screen 14: User Profile Screen
* **Layout Structure**:
  * Circular profile image at the top with user verification badge.
  * Listening statistics row: Hours listened, Top genre, Total tracks played.
  * Flex-layout of **Favorite Genres** represented by customized dynamic color chips.
  * "Go Premium" card and access shortcut to Settings.

---

### Screen 15: Settings Screen
* **Layout Structure**: Categorized preference screen using Material 3 list dividers:
  * Categories:
    * **Appearance**: Theme picker, Dark Mode configuration, seek bar customization.
    * **Audio Quality**: Streaming quality toggle, system equalizer panel path.
    * **Downloads**: Cache limits, offline quality, clear cache.
    * **Notifications**: New releases alert toggle, player control alerts.
    * **Account & Privacy**: User data exports, history pauses.
    * **About**: App version, update checking panels.

---

## 5. Adaptive Layouts & Navigation

To provide premium usability on Foldables, Tablets, and Mobile phones, navigation conforms to active screen sizes:

* **Compact Width (<600dp)**: Standard bottom `NavigationBar` with 4 main navigation categories (Home, Search, Library, Settings).
* **Medium Width (600dp - 840dp)**: Navigation adapts to a vertical `NavigationRail` pinned to the left edge of the screen.
* **Expanded Width (>840dp)**: Navigation converts to a full-screen vertical Navigation Drawer containing text labels and actions, utilizing two-column responsive screen splits.
* **Foldable Support**: Screens react to half-open (tabletop) states by splitting controls to the bottom screen half and artwork to the top half.
