<div align="center">

<img src="app/src/main/res/drawable/ic_splash_logo.png" alt="Sonic Music Logo" width="120" height="120"/>

# Sonic Music

**A modern, expressive, and lightning-fast Android music streaming app —**  
**powered by YouTube Music's global catalog.**

<br/>

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-brightgreen?logo=android&logoColor=white)](https://developer.android.com/about/versions/oreo)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-35%20(Android%2015)-brightgreen?logo=android&logoColor=white)](https://developer.android.com/about/versions/15)
[![Version](https://img.shields.io/badge/Version-2.0.0-orange)](https://github.com/sujalshukla9/SonicMusic/releases)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue?logo=gnu)](https://www.gnu.org/licenses/gpl-3.0)

</div>

---

## 📸 Screenshots

|           Home            |          Player           |      Charts           |          Settings          |
| :-----------------------: | :-----------------------: | :-----------------------: | :-----------------------: |
| <img src="" width="200"/> | <img src="" width="200"/> | <img src="" width="200"/> | <img src="" width="200"/> |

---

## ✨ Features

### 🎵 Playback & Audio
- **Seamless background playback** via Media3 / ExoPlayer with a rich MediaStyle notification
- **Offline mode** — download tracks and listen without internet
- **Audio quality selector** — choose from low, normal, high, or lossless streams
- **Sleep timer** — auto-pause after a set duration
- **System equalizer** integration for tuned audio

### 🎨 UI & Experience
- **Material Design 3 Expressive** — fluid, physics-based animations throughout
- **Dynamic color theming** — the UI palette adapts in real-time to the playing track's album art using Material You `Palette`
- **Gesture-driven player** — swipe-to-dismiss, momentum physics, and spring animations
- **Pull-to-refresh** with M3 Expressive spring bounce
- **Shimmer skeleton loading** states across all screens

### 🌍 Smart Region Detection
- **Fully dynamic region detection** — no hardcoded country or language codes
- Priority pipeline: **YTM InnerTube session → SIM/network country → IP geolocation → device locale**
- Dynamic `gl` (region) and `hl` (language) for all InnerTube API calls
- **24-hour detection cache** — avoids redundant lookups
- **Manual country picker** — searchable list of all 249 ISO countries with flag emojis
- Home and Charts **auto-refresh** when the detected region changes

### 🔍 Discovery & Recommendations
- **Home feed** with personalized sections — Quick Picks, New Releases, Trending, Listen Again
- **Charts** — Top Songs, Artists, and Albums refreshed per region
- **Fresh queue recommendations** — YTM-style "Up Next" seeded from the selected track, scoped per section
- **Search** with pagination and recent history

### 📚 Library & Account
- YouTube Music **account sync** — imports liked music, playlists, and subscriptions via OAuth
- Local music library browser
- Playback history tracking
- Playlist creation and management
- Follow artists and browse their full catalogs

### ⚡ Performance
- **R8 / ProGuard** full minification with custom keep rules
- **Compose Compiler Strong Skipping** for maximum recomposition efficiency
- **Baseline Profiles** for instant startup
- **Disk cache** with TTL-based maintenance for YTM responses
- Coroutines-first threading — no blocking main thread calls

---

## 🛠 Tech Stack & Architecture

```
SonicMusic
├── :app
│   ├── features/          # Feature modules (home, player, search, charts, library, …)
│   │   └── <feature>/
│   │       ├── data/      # Repository impl, remote/local data sources
│   │       ├── domain/    # Use cases, repository interfaces, domain models
│   │       └── presentation/  # ViewModel + Compose screens
│   ├── data/              # Shared data layer (InnerTube, Room, DataStore)
│   ├── domain/            # Shared domain models & interfaces
│   ├── di/                # Hilt dependency injection modules
│   ├── core/              # Network monitor, disk cache, updater utilities
│   ├── shared/            # Reusable Compose components & UI utilities
│   └── theme/             # Material 3 theme, dynamic color, motion utilities
```

| Layer | Technology |
|---|---|
| **Language** | [Kotlin 2.0](https://kotlinlang.org/) |
| **UI** | [Jetpack Compose](https://developer.android.com/jetpack/compose) + [Material Design 3](https://m3.material.io/) |
| **Architecture** | MVVM · Clean Architecture · Feature-first modularization |
| **Dependency Injection** | [Hilt](https://dagger.dev/hilt/) |
| **Async / Reactive** | Coroutines + StateFlow / SharedFlow |
| **Media Playback** | [Media3 / ExoPlayer](https://developer.android.com/media/media3) |
| **Networking** | [OkHttp](https://square.github.io/okhttp/) + custom InnerTube client |
| **Audio Extraction** | Custom [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) integration |
| **Local Storage** | [Room](https://developer.android.com/training/data-storage/room) + [DataStore Preferences](https://developer.android.com/topic/libraries/architecture/datastore) |
| **Image Loading** | [Coil 3](https://coil-kt.github.io/coil/) |
| **Background Work** | [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) |

---

## 🚀 Building from Source

### Prerequisites

| Tool | Version |
|---|---|
| Android Studio | Meerkat / Ladybug or newer |
| JDK | 17+ |
| Android SDK | API 35 (target), API 26 (minimum) |
| Gradle | 8.x (wrapper included) |

### Steps

```bash
# 1. Clone the repository
git clone https://github.com/sujalshukla9/SonicMusic.git
cd SonicMusic

# 2. Open in Android Studio and sync Gradle
#    — or build directly from CLI:

# Debug APK
./gradlew assembleDebug

# Release APK (R8 minified, ~9 MB)
./gradlew assembleRelease
```

> **Tip:** For the smoothest 60 fps animations, always run the **release** build variant. Debug builds disable certain compiler optimizations.

---

## 🔐 YouTube Music Account Sync Setup

Sonic Music supports syncing your YouTube Music library (liked songs, playlists, subscriptions) via Google OAuth.

### 1. Google Cloud Configuration

1. Go to the [Google Cloud Console](https://console.cloud.google.com/) and create or select a project.
2. Enable the **YouTube Data API v3**.
3. Configure the **OAuth consent screen** for the `https://www.googleapis.com/auth/youtube.readonly` scope.
4. Add your Google account as a **Test User** (while the app is in Testing mode).

### 2. Android OAuth Client

1. Create an **Android** OAuth 2.0 client ID with:
   - **Package name:** `com.sonicmusic.app`
   - **SHA-1 fingerprint:** from `./gradlew :app:signingReport`

### 3. In-App Setup

1. Open **Settings → YouTube Music account sync → Connect Google Account**
2. Complete the Google sign-in flow
3. Synced playlists and liked music appear in **Library → Playlists**
4. Tap **Sync now** at any time to refresh

> If the Google consent screen closes unexpectedly, verify the Android OAuth client's SHA-1 matches the installed APK's signing certificate and that your account appears under **Audience → Test users**.

**References:** [Android OAuth setup](https://developer.android.com/identity/authorization) · [YouTube Data API](https://developers.google.com/youtube/data/getting-started)

---

## 🌍 Dynamic Region Detection

Sonic Music automatically determines your content region without any hardcoded country or language codes. The detection pipeline runs in priority order:

```
1. YouTube Music InnerTube session (most accurate — from your YTM account)
     ↓ (fallback if unavailable)
2. SIM card / network country (TelephonyManager)
     ↓ (fallback)
3. IP geolocation (external API)
     ↓ (fallback)
4. Device locale
```

- Results are **cached for 24 hours** to minimize API calls
- If you've set a **manual region**, auto-detection is skipped entirely
- Change your region anytime from **Settings → Region → Select Country**

---

## 📦 Releases

Pre-built APKs are available on the [Releases page](https://github.com/sujalshukla9/SonicMusic/releases).

| Build | Size | Notes |
|---|---|---|
| Release APK | ~9 MB | R8 minified, production-ready |
| Debug APK | larger | Includes debug symbols, logging |

---

## 🤝 Contributing

Contributions, issues, and feature requests are welcome!  
Feel free to open a [GitHub Issue](https://github.com/sujalshukla9/SonicMusic/issues) or submit a Pull Request.

1. Fork the repository
2. Create your feature branch: `git checkout -b feat/amazing-feature`
3. Commit your changes: `git commit -m 'feat: add amazing feature'`
4. Push to the branch: `git push origin feat/amazing-feature`
5. Open a Pull Request

---

## ⚠️ Disclaimer

> This project uses publicly available data and stream sources provided by third parties (YouTube / YouTube Music).  
> It is intended **for educational and personal use only** and is not affiliated with or endorsed by Google or YouTube.  
> Please respect the [YouTube Terms of Service](https://www.youtube.com/t/terms).

---

<div align="center">

Made with ❤️ by [Sujal Shukla](https://github.com/sujalshukla9)

</div>
