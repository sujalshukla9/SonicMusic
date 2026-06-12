<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" alt="Sonic Music Logo" width="120" height="120">
  <h1>Sonic Music 🎵</h1>
  <p><b>A modern, expressive, and lightning-fast Android music streaming app.</b></p>
  
  [![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
  [![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-blueviolet?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
  [![License](https://img.shields.io/badge/License-GPL%20v3-green.svg)](https://www.gnu.org/licenses/gpl-3.0)
</div>

---

## ✨ Features

Sonic Music is designed with a focus on advanced performance, a premium Material Design 3 Expressive UI, and seamless audio playback. Built on top of YouTube Music's robust global catalog.

- **Dynamic Theme Engine**: Colors adapt dynamically to the currently playing song's album art using Material You `Palette`.
- **Offline Playback**: Download your favorite tracks and listen anywhere without an internet connection.
- **Advanced Player UI**: Features a gesture-driven `ViTune`-style physics-based swipe player for insanely smooth track navigation.
- **Background Playback**: Flawless audio continuation in the background with Media3 and a rich MediaStyle notification.
- **Sleep Timer**: Fall asleep to your music and let the app handle the pause.
- **Audio Equalizer**: Quick access to system EQ settings.
- **Personalized Library**: Follow artists, create playlists, and track your listening history.
- **Lightning Fast**: Heavily optimized with custom `ProGuard` rules, Compose Compiler Strong Skipping, Baseline Profiles, and robust Coroutines caching.

---

## 📸 Screenshots

_(Add your screenshots here later)_

|           Home            |          Player           |      Artist Profile       |          Library          |
| :-----------------------: | :-----------------------: | :-----------------------: | :-----------------------: |
| <img src="" width="200"/> | <img src="" width="200"/> | <img src="" width="200"/> | <img src="" width="200"/> |

---

## 🛠 Tech Stack & Architecture

Engineered using the latest Android development standards and best practices:

- **Language**: [Kotlin](https://kotlinlang.org/)
- **UI**: [Jetpack Compose](https://developer.android.com/jetpack/compose) & [Material Design 3](https://m3.material.io/)
- **Architecture**: MVVM + Clean Architecture Principles
- **Dependency Injection**: [Hilt](https://dagger.dev/hilt/)
- **Asynchronous Programming**: Coroutines & Flows
- **Media Playback**: [ExoPlayer / Media3](https://developer.android.com/media/media3)
- **Local Storage**: [Room Database](https://developer.android.com/training/data-storage/room) & [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- **Image Loading**: [Coil](https://coil-kt.github.io/coil/)
- **Data Extraction**: Custom NewPipe Extractor integration for fetching stream sources.

---

## 🚀 Building from Source

### Prerequisites

- Android Studio Ladybug (or newer)
- JDK 17
- Minimum SDK 26 (Android 8.0)

### Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/SonicMusic.git
   ```
2. Open the project in Android Studio.
3. Sync the project with Gradle files.
4. Run the app directly to an emulator or physical device via the `app` run configuration.

_Note: For maximum performance and smooth 60fps animations, compile using the `release` build variant._

---

> **Disclaimer:** This app uses data and streams provided by third parties (YouTube). It is intended for educational purposes and personal use.
