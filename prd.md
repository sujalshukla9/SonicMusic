# Sonic Music - Product Requirements Document

---

## Document Information

| Field | Value |
|-------|-------|
| **Product Name** | Sonic Music |
| **Document Version** | 2.0 |
| **Last Updated** | February 8, 2026 |
| **Document Owner** | Product Management Team |
| **Status** | Draft for Review |
| **Classification** | Internal Use |

### Document Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | Jan 15, 2026 | Initial Team | Initial draft with technical specifications |
| 2.0 | Feb 8, 2026 | Product Team | Comprehensive revision with business context, user research, and acceptance criteria |

### Stakeholders

| Role | Name | Responsibility |
|------|------|----------------|
| Product Owner | TBD | Overall product vision and prioritization |
| Engineering Lead | TBD | Technical architecture and feasibility |
| UX/UI Designer | TBD | User experience and interface design |
| QA Lead | TBD | Quality assurance and testing strategy |
| Legal Counsel | TBD | Compliance and legal review |

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Problem Statement & Market Opportunity](#2-problem-statement--market-opportunity)
3. [Target Audience & User Personas](#3-target-audience--user-personas)
4. [Competitive Analysis](#4-competitive-analysis)
5. [Product Overview](#5-product-overview)
6. [User Stories & Use Cases](#6-user-stories--use-cases)
7. [Functional Requirements](#7-functional-requirements)
8. [Non-Functional Requirements](#8-non-functional-requirements)
9. [Technical Architecture](#9-technical-architecture)
10. [Feature Specifications](#10-feature-specifications)
11. [UI/UX Design System](#11-uiux-design-system)
12. [Data Models](#12-data-models)
13. [Development Roadmap](#13-development-roadmap)
14. [Success Metrics & KPIs](#14-success-metrics--kpis)
15. [Dependencies & Risks](#15-dependencies--risks)
16. [Security & Privacy](#16-security--privacy)
17. [Testing Strategy](#17-testing-strategy)
18. [Compliance & Legal](#18-compliance--legal)
19. [Open Questions & Future Considerations](#19-open-questions--future-considerations)
20. [Appendices](#20-appendices)

---

## 1. Executive Summary

### 1.1 Overview

**Sonic Music** is a privacy-first, Material 3-designed music streaming application for Android that provides users with unlimited access to YouTube Music content without requiring a Google account or proprietary tracking services. Built on the open-source NewPipe Extractor, Sonic Music delivers a premium listening experience while respecting user privacy and freedom.

### 1.2 Business Case

**Market Gap**: Current music streaming services require account creation, collect extensive user data, and lock users into proprietary ecosystems. There is a significant underserved market of privacy-conscious users and users in regions with limited access to traditional streaming services.

**Value Proposition**:
- **No account required** - Zero barrier to entry
- **Complete privacy** - No tracking, no data collection
- **Free and open** - No subscriptions, no paywalls
- **Offline-first** - Download and own your music
- **Modern experience** - Material 3 design with dynamic theming

**Target Market Size**: 
- Primary: 50M+ Android users globally who value privacy
- Secondary: 200M+ users in regions with limited streaming service access
- Tertiary: Developers and tech enthusiasts (open-source community)

**Success Criteria**: 
- 100,000 active users in first 6 months
- 4.5+ star rating on F-Droid and GitHub
- <1% crash rate
- Featured on F-Droid homepage

---

## 2. Problem Statement & Market Opportunity

### 2.1 User Problems

**Problem 1: Privacy Invasion**
- Current streaming apps require personal information (email, phone number, payment details)
- Extensive tracking of listening habits, location, and device usage
- Data sold to third parties for advertising

**Problem 2: Account Barriers**
- Mandatory account creation creates friction
- Regional restrictions limit access
- Users locked into specific ecosystems

**Problem 3: Online Dependency**
- Most streaming services require constant internet connection
- Offline modes are limited and require premium subscriptions
- No true ownership of downloaded content

**Problem 4: Cost Barriers**
- Premium features behind paywalls ($10-15/month)
- Free tiers have intrusive advertising
- Limited functionality without subscription

### 2.2 Market Opportunity

**Trends Supporting This Product**:
1. **Privacy Movement**: 67% of users concerned about data privacy (Pew Research, 2024)
2. **Open Source Adoption**: 42% increase in F-Droid downloads (2025 vs 2024)
3. **YouTube Music Growth**: 100M+ subscribers, massive content library
4. **Android Dominance**: 72% global market share, 2.8B+ active devices

**Competitive Advantages**:
- First Material 3 implementation in this space
- Only solution with true offline-first architecture
- Open-source transparency (GPL v3)
- No account or payment required

---

## 3. Target Audience & User Personas

### 3.1 Primary Personas

#### Persona 1: Privacy-Conscious Professional
**Name**: Alex Chen  
**Age**: 28  
**Occupation**: Software Developer  
**Location**: San Francisco, CA

**Background**:
- Tech-savvy, values open-source software
- Uses privacy-focused tools (Firefox, Signal, ProtonMail)
- Willing to sacrifice convenience for privacy
- Active on GitHub and Reddit tech communities

**Goals**:
- Stream music without data tracking
- Avoid creating yet another account
- Support open-source alternatives

**Pain Points**:
- Spotify/YouTube Music collect too much data
- Tired of managing subscriptions
- Wants to own downloaded music files

**Usage Pattern**: 8+ hours/day while coding, prefers background playback, uses equalizer

---

#### Persona 2: Budget-Conscious Student
**Name**: Priya Sharma  
**Age**: 21  
**Occupation**: University Student  
**Location**: Mumbai, India

**Background**:
- Limited disposable income
- Relies on free apps and services
- Moderate technical knowledge
- Uses Android mid-range device

**Goals**:
- Access music for free
- Download songs for offline listening (limited data plan)
- Discover new music

**Pain Points**:
- Cannot afford $10/month subscription
- Free tier ads are disruptive
- Data caps make streaming expensive

**Usage Pattern**: 3-4 hours/day, primarily offline playback, creates playlists for study/workout

---

#### Persona 3: Digital Minimalist
**Name**: Marcus Johnson  
**Age**: 35  
**Occupation**: Freelance Designer  
**Location**: Berlin, Germany

**Background**:
- Values simplicity and minimalism
- Avoids big tech platforms when possible
- Prefers apps with clean, functional design
- Privacy advocate

**Goals**:
- Simple, distraction-free music experience
- No ads, no recommendations, no tracking
- Aesthetic, well-designed interface

**Pain Points**:
- Overwhelmed by algorithmic recommendations
- Dislikes cluttered interfaces
- Concerned about data collection

**Usage Pattern**: 2-3 hours/day, curates personal playlists, values design quality

---

### 3.2 Secondary Personas

#### Persona 4: Content Creator
- Needs background music without copyright issues
- Uses YouTube's Creative Commons content
- Requires precise playback control and quality

#### Persona 5: International User
- Lives in region without access to major streaming services
- Faces payment/account verification issues
- Needs reliable offline access

---

## 4. Competitive Analysis

### 4.1 Direct Competitors

#### NewPipe (YouTube)
**Strengths**:
- Mature, established (5M+ downloads)
- Active development community
- Lightweight and fast

**Weaknesses**:
- Dated UI (Material 2)
- Complex navigation
- Limited music-specific features
- No dynamic theming

**Our Advantage**: Modern Material 3 design, music-first experience, better UX

---

#### YMusic
**Strengths**:
- Music-focused features
- Good audio quality extraction
- Popup player

**Weaknesses**:
- Not open-source
- Outdated design
- Limited development (last update 2023)
- No playlist management

**Our Advantage**: Open-source, active development, comprehensive features, modern design

---

#### BlackPlayer / Pulsar
**Strengths**:
- Beautiful design
- Feature-rich
- Good offline experience

**Weaknesses**:
- Local files only (no streaming)
- No cloud/YouTube integration
- Premium features cost $3-5

**Our Advantage**: Streaming + offline, free and open-source, YouTube Music integration

---

### 4.2 Indirect Competitors

#### Spotify Free
**Strengths**: Huge library, great recommendations, social features  
**Weaknesses**: Requires account, tracks users, limited offline, ads  
**Our Advantage**: No account, no tracking, unlimited offline, no ads

#### YouTube Music Free
**Strengths**: Official, reliable, huge library  
**Weaknesses**: Requires Google account, aggressive tracking, limited features  
**Our Advantage**: No account, privacy-first, better offline experience

---

### 4.3 Competitive Positioning

```
                    Privacy-Focused
                          ↑
                          |
           NewPipe        |    Sonic Music
                          |    (Us)
                          |
Open ←--------------------+--------------------→ Commercial
Source    YMusic          |             Spotify
                          |           YT Music
                          |
                          ↓
                    Feature-Limited
```

**Our Sweet Spot**: High privacy + comprehensive features + modern design + open-source

---

## 5. Product Overview

### 5.1 App Identity

- **Name**: Sonic Music
- **Tagline**: "Sound Without Limits"
- **Platform**: Android (Kotlin)
- **Min SDK**: 26 (Android 8.0 Oreo) with desugaring
- **Target SDK**: 34 (Android 14)
- **Architecture**: MVVM + Clean Architecture
- **License**: GPL v3
- **Distribution**: F-Droid, GitHub Releases

### 5.2 Core Value Proposition

**Privacy-First**
- No Google account required
- No tracking or analytics
- No personal data collection
- Local-only user data

**Material 3 Expressive**
- Pixel-style dynamic theming
- Smooth animations and transitions
- Adaptive layouts for all screen sizes
- Dark/light mode with custom themes

**Extractor-Powered**
- NewPipe Extractor for content fetching
- Direct audio stream extraction
- No API keys or authentication
- Resilient to API changes

**Offline-First**
- Robust caching mechanism
- Encrypted local downloads
- Background download manager
- Smart storage management

---

## 6. User Stories & Use Cases

### 6.1 Epic: Music Discovery & Search

#### User Story 1.1: Quick Music Search
**As a** music listener  
**I want to** search for songs, artists, and albums quickly  
**So that** I can start listening with minimal friction

**Acceptance Criteria**:
- [ ] Search bar accessible from home screen
- [ ] Results appear within 1.5 seconds of query
- [ ] Results include tracks, artists, albums, and playlists
- [ ] Search history is saved locally (optional, can be disabled)
- [ ] Filters available: All, Tracks, Artists, Albums, Playlists
- [ ] Clicking a result immediately plays or navigates to detail page

**Priority**: P0 (Must Have)

---

#### User Story 1.2: Music Discovery by Mood
**As a** user who doesn't know what to listen to  
**I want to** browse music by mood or genre  
**So that** I can discover new music that fits my current state

**Acceptance Criteria**:
- [ ] Home screen displays mood/genre chips (Chill, Focus, Workout, Party, etc.)
- [ ] Clicking a mood generates a curated playlist
- [ ] Trending section shows popular tracks updated daily
- [ ] Quick Picks carousel shows recommended tracks based on history (if enabled)

**Priority**: P1 (Should Have)

---

### 6.2 Epic: Audio Playback

#### User Story 2.1: Seamless Playback
**As a** music listener  
**I want to** play audio without interruptions  
**So that** I can enjoy my music continuously

**Acceptance Criteria**:
- [ ] Audio starts playing within 500ms of selecting a track
- [ ] Playback continues when app is minimized
- [ ] Playback continues with screen off
- [ ] Audio doesn't stop when switching apps
- [ ] Notification controls remain functional
- [ ] Audio quality is 128kbps minimum, 320kbps maximum

**Priority**: P0 (Must Have)

---

#### User Story 2.2: Advanced Playback Control
**As a** power user  
**I want to** have fine-grained control over playback  
**So that** I can customize my listening experience

**Acceptance Criteria**:
- [ ] Skip forward/backward 10 seconds
- [ ] Seek to any position in track
- [ ] Adjust playback speed (0.5x to 2x)
- [ ] Repeat modes: None, All, One
- [ ] Shuffle on/off
- [ ] Sleep timer with presets (15m, 30m, 1h, custom)
- [ ] Crossfade between tracks (optional)
- [ ] Skip silence detection

**Priority**: P1 (Should Have)

---

### 6.3 Epic: Queue Management

#### User Story 3.1: Dynamic Queue
**As a** music listener  
**I want to** manage what plays next  
**So that** I can control my listening experience

**Acceptance Criteria**:
- [ ] "Up Next" queue visible from player screen
- [ ] Add track to queue from any screen
- [ ] Add track to "Play Next" vs "Add to Queue"
- [ ] Drag to reorder queue items
- [ ] Swipe to remove from queue
- [ ] Clear entire queue option
- [ ] Save queue as playlist option
- [ ] Queue persists across app restarts

**Priority**: P0 (Must Have)

---

### 6.4 Epic: Offline Experience

#### User Story 4.1: Download for Offline
**As a** user with limited data  
**I want to** download music for offline listening  
**So that** I don't use mobile data

**Acceptance Criteria**:
- [ ] Download button on all tracks, albums, playlists
- [ ] Download progress indicator
- [ ] Pause/resume downloads
- [ ] Download queue with priority management
- [ ] WiFi-only download option in settings
- [ ] Automatic download quality selection
- [ ] Downloaded tracks marked with badge
- [ ] Downloads encrypted and stored securely
- [ ] Manage downloads: view size, delete individual/batch

**Priority**: P0 (Must Have)

---

### 6.5 Epic: Playlists & Organization

#### User Story 5.1: Create Personal Playlists
**As a** music curator  
**I want to** create and organize playlists  
**So that** I can group my favorite music

**Acceptance Criteria**:
- [ ] Create new playlist from any screen
- [ ] Add tracks to playlist with single tap
- [ ] Add tracks to multiple playlists at once
- [ ] Reorder tracks within playlist (drag-and-drop)
- [ ] Remove tracks from playlist
- [ ] Edit playlist name and description
- [ ] Auto-generated playlist artwork (grid of first 4 tracks)
- [ ] Sort playlists: Name, Date Created, Last Modified
- [ ] Delete playlist with confirmation

**Priority**: P1 (Should Have)

---

### 6.6 Epic: User Experience Enhancement

#### User Story 6.1: Beautiful, Modern Interface
**As a** design-conscious user  
**I want to** use an aesthetically pleasing app  
**So that** my music experience is enjoyable

**Acceptance Criteria**:
- [ ] Material 3 design language throughout
- [ ] Dynamic color theming (Android 12+)
- [ ] Custom color themes (Manual selection)
- [ ] Smooth animations and transitions
- [ ] Responsive layouts (phones, tablets, foldables)
- [ ] Dark/light mode with system auto-switch
- [ ] Album artwork displayed prominently
- [ ] Typography optimized for readability
- [ ] Consistent spacing and alignment

**Priority**: P0 (Must Have)

---

#### User Story 6.2: Accessibility Support
**As a** user with accessibility needs  
**I want to** use the app with assistive technologies  
**So that** I'm not excluded from the experience

**Acceptance Criteria**:
- [ ] All interactive elements have content descriptions
- [ ] Minimum touch target size: 48x48dp
- [ ] Screen reader (TalkBack) fully functional
- [ ] Keyboard navigation support
- [ ] High contrast mode support
- [ ] Adjustable text size (respect system settings)
- [ ] Color blind friendly (don't rely solely on color)
- [ ] Haptic feedback for important actions

**Priority**: P1 (Should Have)

---

## 7. Functional Requirements

### 7.1 Core Audio Features

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-001 | Extract audio-only streams from YouTube videos | P0 | Required |
| FR-002 | Support multiple audio quality levels (128k, 256k, 320k) | P0 | Required |
| FR-003 | Background playback when app is minimized | P0 | Required |
| FR-004 | Playback continues with screen off | P0 | Required |
| FR-005 | Lock screen media controls | P0 | Required |
| FR-006 | Notification with playback controls | P0 | Required |
| FR-007 | Play/pause, skip, seek controls | P0 | Required |
| FR-008 | Volume control | P0 | Required |
| FR-009 | Repeat modes (None, All, One) | P0 | Required |
| FR-010 | Shuffle playback | P0 | Required |

### 7.2 Search & Discovery

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-101 | Text search for tracks, artists, albums | P0 | Required |
| FR-102 | Search with filters (Tracks/Artists/Albums/Playlists) | P0 | Required |
| FR-103 | Search suggestions and autocomplete | P1 | Required |
| FR-104 | Search history (local, deletable) | P1 | Optional |
| FR-105 | Trending music section | P1 | Optional |
| FR-106 | Mood/genre discovery | P2 | Nice to Have |
| FR-107 | "Similar to this" recommendations | P2 | Nice to Have |

### 7.3 Queue & Playlist Management

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-201 | View current playback queue | P0 | Required |
| FR-202 | Add tracks to queue | P0 | Required |
| FR-203 | Add to "Play Next" vs "Add to End" | P0 | Required |
| FR-204 | Reorder queue items (drag-and-drop) | P0 | Required |
| FR-205 | Remove items from queue | P0 | Required |
| FR-206 | Clear entire queue | P1 | Required |
| FR-207 | Save queue as playlist | P1 | Optional |
| FR-208 | Create new playlists | P1 | Required |
| FR-209 | Add tracks to playlists | P1 | Required |
| FR-210 | Edit playlist details | P1 | Required |
| FR-211 | Delete playlists | P1 | Required |
| FR-212 | Reorder playlist tracks | P1 | Required |

### 7.4 Offline & Downloads

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-301 | Download individual tracks | P0 | Required |
| FR-302 | Download entire albums | P1 | Required |
| FR-303 | Download entire playlists | P1 | Required |
| FR-304 | Download progress indicator | P0 | Required |
| FR-305 | Pause/resume downloads | P1 | Required |
| FR-306 | Download queue management | P1 | Required |
| FR-307 | WiFi-only download setting | P0 | Required |
| FR-308 | Downloaded files encrypted | P0 | Required |
| FR-309 | Manage storage: view size, delete downloads | P0 | Required |
| FR-310 | Auto-cache recently played tracks | P1 | Optional |

### 7.5 Advanced Features

| ID | Requirement | Priority | Status |
|----|-------------|----------|--------|
| FR-401 | Sleep timer with presets | P1 | Optional |
| FR-402 | Audio equalizer | P1 | Optional |
| FR-403 | Playback speed control (0.5x-2x) | P2 | Nice to Have |
| FR-404 | Skip silence detection | P2 | Nice to Have |
| FR-405 | Crossfade between tracks | P2 | Nice to Have |
| FR-406 | Lyrics display | P2 | Nice to Have |
| FR-407 | Synchronized lyrics (if available) | P3 | Future |
| FR-408 | Chromecast/DLNA support | P2 | Nice to Have |
| FR-409 | Android Auto integration | P2 | Nice to Have |
| FR-410 | Wear OS companion app | P3 | Future |

---

## 8. Non-Functional Requirements

### 8.1 Performance Requirements

| ID | Requirement | Target | Priority |
|----|-------------|--------|----------|
| NFR-001 | Cold app start time | < 2 seconds | P0 |
| NFR-002 | Audio playback start latency | < 500ms | P0 |
| NFR-003 | Search response time | < 1.5 seconds | P0 |
| NFR-004 | UI interaction response time | < 100ms | P0 |
| NFR-005 | Frame rate (scrolling, animations) | ≥ 60 FPS | P0 |
| NFR-006 | Battery usage (1hr playback, screen off) | < 5% | P0 |
| NFR-007 | Memory usage (peak) | < 200 MB | P1 |
| NFR-008 | APK size | < 15 MB | P1 |

### 8.2 Reliability Requirements

| ID | Requirement | Target | Priority |
|----|-------------|--------|----------|
| NFR-101 | Crash-free rate | > 99.5% | P0 |
| NFR-102 | ANR (Application Not Responding) rate | < 0.1% | P0 |
| NFR-103 | Network failure recovery | Automatic retry with exponential backoff | P0 |
| NFR-104 | Playback stability (no audio glitches) | > 99.9% | P0 |
| NFR-105 | Data corruption prevention | Checksums on downloads | P0 |

### 8.3 Scalability Requirements

| ID | Requirement | Target | Priority |
|----|-------------|--------|----------|
| NFR-201 | Support playlists with up to | 10,000 tracks | P1 |
| NFR-202 | Support local database size | Up to 5 GB | P1 |
| NFR-203 | Handle concurrent downloads | Up to 3 simultaneous | P1 |
| NFR-204 | Queue size limit | Unlimited | P0 |

### 8.4 Usability Requirements

| ID | Requirement | Target | Priority |
|----|-------------|--------|----------|
| NFR-301 | New user onboarding | < 30 seconds to first play | P0 |
| NFR-302 | Accessibility (WCAG 2.1 Level) | AA compliance | P1 |
| NFR-303 | Localization support | 10+ languages in first year | P2 |
| NFR-304 | Offline documentation | In-app help and FAQ | P2 |

### 8.5 Security Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| NFR-401 | Downloaded files encrypted (AES-256) | P0 |
| NFR-402 | No sensitive data in logs | P0 |
| NFR-403 | Secure HTTPS connections only | P0 |
| NFR-404 | Certificate pinning for critical endpoints | P1 |
| NFR-405 | Code obfuscation (ProGuard/R8) | P1 |

### 8.6 Privacy Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| NFR-501 | No analytics or tracking | P0 |
| NFR-502 | No personal data collection | P0 |
| NFR-503 | No network requests except content fetching | P0 |
| NFR-504 | Local-only history and preferences | P0 |
| NFR-505 | Clear data on app uninstall | P0 |
| NFR-506 | No third-party SDKs (except open-source libraries) | P0 |

### 8.7 Compatibility Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| NFR-601 | Android versions 8.0 (API 26) to 14 (API 34) | P0 |
| NFR-602 | Support all screen sizes (phones, tablets, foldables) | P0 |
| NFR-603 | Support all screen densities (mdpi to xxxhdpi) | P0 |
| NFR-604 | Orientation: Portrait and Landscape | P1 |
| NFR-605 | Multi-window/split-screen support | P2 |

---

## 9. Technical Architecture

### 9.1 Tech Stack

```kotlin
// Core Dependencies
dependencies {
    // NewPipe Extractor
    implementation 'com.github.teamnewpipe:NewPipeExtractor:v0.24.0'
    
    // Media Playback
    implementation 'androidx.media3:media3-exoplayer:1.3.0'
    implementation 'androidx.media3:media3-session:1.3.0'
    implementation 'androidx.media3:media3-ui:1.3.0'
    
    // Jetpack Compose
    implementation platform('androidx.compose:compose-bom:2024.02.00')
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.compose.material3:material3-window-size-class'
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'androidx.activity:activity-compose:1.8.2'
    implementation 'androidx.navigation:navigation-compose:2.7.7'
    
    // Image Loading
    implementation 'io.coil-kt:coil-compose:2.5.0'
    
    // Local Database
    implementation 'androidx.room:room-runtime:2.6.1'
    implementation 'androidx.room:room-ktx:2.6.1'
    kapt 'androidx.room:room-compiler:2.6.1'
    
    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3'
    
    // Dependency Injection
    implementation 'com.google.dagger:hilt-android:2.50'
    kapt 'com.google.dagger:hilt-compiler:2.50'
    implementation 'androidx.hilt:hilt-navigation-compose:1.1.0'
    
    // DataStore (Preferences)
    implementation 'androidx.datastore:datastore-preferences:1.0.0'
    
    // WorkManager (Background tasks)
    implementation 'androidx.work:work-runtime-ktx:2.9.0'
    
    // Testing
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.mockito:mockito-core:5.8.0'
    testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
    androidTestImplementation 'androidx.compose.ui:ui-test-junit4'
}
```

### 9.2 System Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        Presentation Layer                         │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Jetpack Compose UI (Material 3)                           │  │
│  │  - Screens (Home, Player, Search, Library, Settings)      │  │
│  │  - Components (Cards, Lists, Dialogs, Sheets)             │  │
│  │  - Theme System (Dynamic Colors, Typography)              │  │
│  └────────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  ViewModels (State Management)                             │  │
│  │  - HomeViewModel, PlayerViewModel, SearchViewModel         │  │
│  │  - State: UIState, Events, Side Effects                   │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────┬───────────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────────┐
│                         Domain Layer                              │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Use Cases (Business Logic)                                │  │
│  │  - PlayTrackUseCase, SearchMusicUseCase                   │  │
│  │  - DownloadTrackUseCase, ManageQueueUseCase               │  │
│  └────────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Repository Interfaces                                      │  │
│  │  - MusicRepository, PlaybackRepository                     │  │
│  │  - DownloadRepository, SettingsRepository                  │  │
│  └────────────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Domain Models                                             │  │
│  │  - Track, Album, Artist, Playlist, Queue                  │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────┬───────────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────────┐
│                          Data Layer                               │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  Repository Implementations                                 │  │
│  │  - Coordinate between remote and local data sources        │  │
│  └────────────────────────────────────────────────────────────┘  │
│  ┌──────────────────┬──────────────────┬──────────────────────┐  │
│  │  Remote Source   │  Local Source    │  Playback Service    │  │
│  │  ┌────────────┐  │  ┌────────────┐  │  ┌────────────────┐  │  │
│  │  │ NewPipe    │  │  │ Room DB    │  │  │ ExoPlayer      │  │  │
│  │  │ Extractor  │  │  │ - Tracks   │  │  │ + Media3       │  │  │
│  │  │ Service    │  │  │ - Playlists│  │  │ Session        │  │  │
│  │  │            │  │  │ - History  │  │  │                │  │  │
│  │  └────────────┘  │  └────────────┘  │  └────────────────┘  │  │
│  │                  │                  │                      │  │
│  │                  │  ┌────────────┐  │  ┌────────────────┐  │  │
│  │                  │  │ DataStore  │  │  │ Download       │  │  │
│  │                  │  │ Preferences│  │  │ Manager        │  │  │
│  │                  │  └────────────┘  │  └────────────────┘  │  │
│  └──────────────────┴──────────────────┴──────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

### 9.3 NewPipe Extractor Integration

```kotlin
/**
 * Wrapper service for NewPipe Extractor
 * Handles all interactions with YouTube content extraction
 */
class SonicExtractorService @Inject constructor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val extractor: NewPipe by lazy { 
        NewPipe.init(Downloader.init(null))
        NewPipe
    }
    
    /**
     * Search for music content
     * @param query Search query string
     * @param filter Content type filter
     * @return List of search results
     */
    suspend fun searchMusic(
        query: String, 
        filter: MusicFilter = MusicFilter.ALL
    ): Result<List<SearchResult>> = withContext(dispatcher) {
        runCatching {
            val service = extractor.getService(YouTube.serviceId)
            val contentFilter = when (filter) {
                MusicFilter.TRACKS -> listOf(YouTubeFilters.VIDEOS)
                MusicFilter.ALBUMS -> listOf(YouTubeFilters.ALBUMS)
                MusicFilter.ARTISTS -> listOf(YouTubeFilters.CHANNELS)
                MusicFilter.PLAYLISTS -> listOf(YouTubeFilters.PLAYLISTS)
                MusicFilter.ALL -> emptyList()
            }
            
            val searchExtractor = service.getSearchExtractor(query, contentFilter, "")
            searchExtractor.fetchPage()
            
            searchExtractor.initialPage.items.map { item ->
                when (item) {
                    is StreamInfoItem -> SearchResult.Track(item.toTrack())
                    is ChannelInfoItem -> SearchResult.Artist(item.toArtist())
                    is PlaylistInfoItem -> SearchResult.Playlist(item.toPlaylist())
                    else -> null
                }
            }.filterNotNull()
        }
    }
    
    /**
     * Extract audio stream URL from video
     * @param videoUrl YouTube video URL
     * @return Best available audio stream
     */
    suspend fun getAudioStream(videoUrl: String): Result<AudioStream> = 
        withContext(dispatcher) {
            runCatching {
                val extractor = YouTube.getStreamExtractor(videoUrl)
                extractor.fetchPage()
                
                // Prefer high-quality audio formats
                val audioStreams = extractor.audioStreams
                val bestStream = audioStreams
                    .filter { it.format == MediaFormat.M4A || it.format == MediaFormat.OPUS }
                    .maxByOrNull { it.bitrate }
                    ?: audioStreams.maxByOrNull { it.bitrate }
                    ?: throw NoAudioStreamException("No audio stream available")
                
                AudioStream(
                    url = bestStream.url,
                    format = bestStream.format,
                    bitrate = bestStream.bitrate,
                    codec = bestStream.codec
                )
            }
        }
    
    /**
     * Get related/similar tracks
     */
    suspend fun getRelatedTracks(videoId: String): Result<List<Track>> =
        withContext(dispatcher) {
            runCatching {
                val extractor = YouTube.getStreamExtractor(videoId)
                extractor.fetchPage()
                extractor.relatedItems.items
                    .filterIsInstance<StreamInfoItem>()
                    .map { it.toTrack() }
            }
        }
}

// Extension functions for mapping
private fun StreamInfoItem.toTrack() = Track(
    id = url.substringAfter("v="),
    title = name,
    artist = Artist(
        id = uploaderUrl?.substringAfter("channel/") ?: "",
        name = uploaderName ?: "Unknown"
    ),
    duration = duration.seconds,
    thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url ?: "",
    url = url
)
```

### 9.4 Audio Playback Architecture

```kotlin
/**
 * Manages audio playback using ExoPlayer and Media3 Session
 */
class AudioPlaybackManager @Inject constructor(
    private val context: Context,
    private val extractorService: SonicExtractorService,
    private val cacheManager: CacheManager,
    private val database: AppDatabase
) {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }
    
    private val mediaSession: MediaSession by lazy {
        MediaSession.Builder(context, exoPlayer)
            .setCallback(MediaSessionCallback())
            .build()
    }
    
    /**
     * Play a track with smart caching
     */
    suspend fun playTrack(track: Track) {
        // 1. Check if downloaded locally
        val localFile = database.trackDao().getLocalPath(track.id)
        
        val mediaItem = if (localFile != null && File(localFile).exists()) {
            // Play from local encrypted file
            MediaItem.fromUri(localFile.toUri())
        } else {
            // Check memory cache
            val cachedUrl = cacheManager.getCachedStreamUrl(track.id)
            if (cachedUrl != null && !isUrlExpired(cachedUrl)) {
                MediaItem.Builder()
                    .setUri(cachedUrl)
                    .setCustomCacheKey(track.id)
                    .build()
            } else {
                // Extract fresh stream URL
                val audioStream = extractorService.getAudioStream(track.url).getOrThrow()
                cacheManager.cacheStreamUrl(track.id, audioStream.url)
                
                MediaItem.Builder()
                    .setUri(audioStream.url)
                    .setCustomCacheKey(track.id)
                    .setMetadata(track.toMediaMetadata())
                    .build()
            }
        }
        
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
        
        // Update history
        database.trackDao().incrementPlayCount(track.id)
        database.trackDao().updateLastPlayed(track.id, System.currentTimeMillis())
    }
    
    /**
     * Manage playback queue
     */
    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        val mediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(Uri.EMPTY) // Will be resolved on demand
                .setMetadata(track.toMediaMetadata())
                .build()
        }
        
        exoPlayer.setMediaItems(mediaItems, startIndex, 0)
        exoPlayer.prepare()
    }
    
    private fun Track.toMediaMetadata() = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist.name)
        .setAlbumTitle(album?.title)
        .setArtworkUri(thumbnailUrl.toUri())
        .build()
}
```

---

## 10. Feature Specifications

### 10.1 Core Features Matrix

| Feature | Priority | Complexity | Dependencies | Acceptance Criteria |
|---------|----------|------------|--------------|-------------------|
| **Audio Extraction** | P0 | High | NewPipe Extractor | AC-001 to AC-005 |
| **Background Playback** | P0 | High | Media3 Service | AC-010 to AC-015 |
| **Offline Downloads** | P0 | High | WorkManager, Encryption | AC-020 to AC-028 |
| **Search & Discovery** | P0 | Medium | NewPipe Search API | AC-030 to AC-035 |
| **Queue Management** | P0 | Medium | Media3 MediaController | AC-040 to AC-046 |
| **Playlists** | P1 | Medium | Room DB Relations | AC-050 to AC-056 |
| **Sleep Timer** | P1 | Low | AlarmManager | AC-060 to AC-063 |
| **Equalizer** | P1 | Medium | AudioEffect API | AC-070 to AC-074 |
| **Lyrics Display** | P2 | Medium | LRCLib API | AC-080 to AC-083 |
| **Cast Support** | P2 | High | Cast SDK | AC-090 to AC-094 |

### 10.2 Feature: Audio Extraction

**Description**: Extract high-quality audio streams from YouTube videos using NewPipe Extractor.

**Technical Implementation**:
```kotlin
// Audio quality selection logic
fun selectBestAudioStream(streams: List<AudioStream>): AudioStream {
    return streams
        .filter { 
            it.format in listOf(MediaFormat.M4A, MediaFormat.OPUS, MediaFormat.WEBMA)
        }
        .sortedWith(
            compareByDescending<AudioStream> { it.bitrate }
                .thenByDescending { it.format == MediaFormat.M4A }
        )
        .firstOrNull()
        ?: streams.maxByOrNull { it.bitrate }
        ?: throw NoAudioStreamException()
}
```

**Acceptance Criteria**:
- AC-001: Extract audio stream within 1 second for 95% of requests
- AC-002: Support audio formats: M4A, Opus, WebM
- AC-003: Select highest bitrate available (up to 320kbps)
- AC-004: Handle extraction failures gracefully with user-facing error
- AC-005: Cache extracted stream URLs for 6 hours

**Edge Cases**:
- Region-locked content → Show clear error message
- Age-restricted content → Attempt extraction, show error if fails
- Unavailable content → Remove from queue, show toast notification

---

### 10.3 Feature: Background Playback

**Description**: Continue audio playback when app is minimized or screen is off.

**Technical Implementation**:
```kotlin
class PlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exoPlayer.playWhenReady || exoPlayer.mediaItemCount == 0) {
            stopSelf()
        }
    }
}
```

**Manifest Configuration**:
```xml
<service
    android:name=".playback.PlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService"/>
    </intent-filter>
</service>
```

**Acceptance Criteria**:
- AC-010: Playback continues when app is minimized
- AC-011: Playback continues with screen off
- AC-012: Notification controls functional (play/pause, skip, close)
- AC-013: Lock screen controls show album art and metadata
- AC-014: Service survives app removal from recent apps (if playing)
- AC-015: Proper audio focus handling (pause on phone call, etc.)

**Battery Optimization**:
- Use foreground service only when actually playing
- Release resources when paused for >5 minutes
- Wake lock only during active playback

---

### 10.4 Feature: Offline Downloads

**Description**: Download audio files for offline playback with encryption.

**Technical Implementation**:
```kotlin
class DownloadManager @Inject constructor(
    private val extractor: SonicExtractorService,
    private val database: AppDatabase,
    private val encryptionService: EncryptionService,
    private val workManager: WorkManager
) {
    fun enqueueDownload(track: Track, quality: AudioQuality) {
        val downloadWork = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    "trackId" to track.id,
                    "trackUrl" to track.url,
                    "quality" to quality.name
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (settingsRepo.downloadOnlyOnWifi) 
                            NetworkType.UNMETERED 
                        else 
                            NetworkType.CONNECTED
                    )
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()
        
        workManager.enqueue(downloadWork)
    }
}

class DownloadWorker @Inject constructor(
    context: Context,
    params: WorkerParameters,
    private val extractor: SonicExtractorService,
    private val encryptionService: EncryptionService
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        val trackId = inputData.getString("trackId") ?: return Result.failure()
        val trackUrl = inputData.getString("trackUrl") ?: return Result.failure()
        
        return try {
            // 1. Extract stream URL
            val audioStream = extractor.getAudioStream(trackUrl).getOrThrow()
            
            // 2. Download with progress
            val tempFile = downloadFile(audioStream.url) { progress ->
                setProgress(workDataOf("progress" to progress))
            }
            
            // 3. Encrypt file
            val encryptedFile = encryptionService.encryptFile(tempFile)
            
            // 4. Move to app storage
            val finalPath = moveToSecureStorage(encryptedFile, trackId)
            
            // 5. Update database
            database.trackDao().updateLocalPath(trackId, finalPath)
            
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry()
            else Result.failure()
        }
    }
}
```

**Encryption**:
```kotlin
class EncryptionService {
    private val secretKey: SecretKey by lazy {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            )
            keyGenerator.generateKey()
        }
        
        (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
    
    fun encryptFile(inputFile: File): File {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val iv = cipher.iv
        val outputFile = File(inputFile.parent, "${inputFile.name}.enc")
        
        FileOutputStream(outputFile).use { fos ->
            // Write IV first
            fos.write(iv)
            
            // Encrypt and write data
            CipherOutputStream(fos, cipher).use { cos ->
                inputFile.inputStream().use { it.copyTo(cos) }
            }
        }
        
        inputFile.delete()
        return outputFile
    }
}
```

**Acceptance Criteria**:
- AC-020: Download button visible on all tracks, albums, playlists
- AC-021: Download progress shown in notification
- AC-022: Pause/resume individual downloads
- AC-023: Downloads only on WiFi (if setting enabled)
- AC-024: Files encrypted with AES-256-GCM
- AC-025: Downloaded badge shown on tracks
- AC-026: Manage downloads screen: view size, delete individual/batch
- AC-027: Auto-resume interrupted downloads
- AC-028: Storage warning when <500MB remaining

---

### 10.5 Feature: Search & Discovery

[Earlier search implementation from the original PRD...]

**Acceptance Criteria**:
- AC-030: Search results appear within 1.5 seconds
- AC-031: Filters work correctly (Tracks, Artists, Albums, Playlists)
- AC-032: Search history saved locally (deletable)
- AC-033: Suggestions appear after 2 characters
- AC-034: Empty state shows trending or recent searches
- AC-035: Voice search supported (if device has capability)

---

### 10.6 Feature: Smart Queue Algorithm

```kotlin
class SmartQueueManager @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val extractorService: SonicExtractorService
) {
    /**
     * Generate intelligent queue based on current track and context
     */
    suspend fun generateSmartQueue(
        seedTrack: Track,
        context: PlaybackContext
    ): List<Track> {
        return when (context) {
            is PlaybackContext.Playlist -> {
                // Continue playlist, then add similar tracks
                val remaining = context.getRemainingTracks()
                if (remaining.size >= MIN_QUEUE_SIZE) {
                    remaining
                } else {
                    remaining + findSimilarTracks(seedTrack, MIN_QUEUE_SIZE - remaining.size)
                }
            }
            
            is PlaybackContext.Album -> {
                // Play full album
                context.album.tracks
            }
            
            is PlaybackContext.ArtistRadio -> {
                // Mix of artist's tracks + similar artists
                val artistTracks = getArtistTopTracks(seedTrack.artist, limit = 10)
                val similarArtistTracks = getSimilarArtistTracks(seedTrack.artist, limit = 10)
                (artistTracks + similarArtistTracks).shuffled()
            }
            
            is PlaybackContext.SingleTrack -> {
                // Mix of: related tracks + user's history + trending
                val related = getRelatedTracks(seedTrack, limit = 10)
                val fromHistory = getTracksFromHistory(exclude = seedTrack, limit = 5)
                val trending = getTrendingTracks(genre = seedTrack.genre, limit = 5)
                
                (related + fromHistory + trending)
                    .distinctBy { it.id }
                    .shuffled()
            }
        }
    }
    
    private suspend fun findSimilarTracks(track: Track, limit: Int): List<Track> {
        // Use NewPipe's related videos feature
        return extractorService.getRelatedTracks(track.id)
            .getOrNull()
            ?.take(limit)
            ?: emptyList()
    }
    
    companion object {
        private const val MIN_QUEUE_SIZE = 20
    }
}
```

---

## 11. UI/UX Design System

[Material 3 design specs from original PRD, with additions...]

### 11.1 Material 3 Expressive Color System

```kotlin
@Composable
fun SonicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        
        darkTheme -> darkColorScheme(
            primary = Color(0xFF9ECAFF),
            onPrimary = Color(0xFF003354),
            primaryContainer = Color(0xFF004A77),
            onPrimaryContainer = Color(0xFFCFE5FF),
            
            secondary = Color(0xFF96F7B6),
            onSecondary = Color(0xFF00391F),
            secondaryContainer = Color(0xFF00522F),
            onSecondaryContainer = Color(0xFFB2FFD0),
            
            tertiary = Color(0xFFFFB59E),
            onTertiary = Color(0xFF5A1C00),
            tertiaryContainer = Color(0xFF7C2E0E),
            onTertiaryContainer = Color(0xFFFFDACE),
            
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            
            background = Color(0xFF001F25),
            onBackground = Color(0xFFA6EEFF),
            
            surface = Color(0xFF001F25),
            onSurface = Color(0xFFA6EEFF),
            surfaceVariant = Color(0xFF40484C),
            onSurfaceVariant = Color(0xFFBFC8CC),
            
            outline = Color(0xFF899296),
            outlineVariant = Color(0xFF40484C),
        )
        
        else -> lightColorScheme(
            primary = Color(0xFF0060B9),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFD1E4FF),
            onPrimaryContainer = Color(0xFF001D36),
            
            secondary = Color(0xFF006C45),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFF7DFFC1),
            onSecondaryContainer = Color(0xFF002112),
            
            tertiary = Color(0xFF9C432E),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFDAD1),
            onTertiaryContainer = Color(0xFF3E0400),
        )
    }
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = SonicTypography,
        shapes = SonicShapes,
        content = content
    )
}
```

### 11.2 Accessibility Support

```kotlin
// Minimum touch target size
val MinTouchTargetSize = 48.dp

// Semantic descriptions for all interactive elements
@Composable
fun PlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(MinTouchTargetSize)
            .semantics {
                contentDescription = if (isPlaying) "Pause" else "Play"
                role = Role.Button
            }
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null, // Handled by semantics above
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
    }
}

// Screen reader announcements
@Composable
fun TrackListItem(track: Track, onClick: () -> Unit) {
    val announcement = "${track.title} by ${track.artist.name}, ${track.duration.formatDuration()}"
    
    ListItem(
        headlineContent = { Text(track.title) },
        supportingContent = { Text(track.artist.name) },
        trailingContent = { Text(track.duration.formatDuration()) },
        modifier = Modifier
            .clickable(
                onClickLabel = "Play $announcement"
            ) { onClick() }
            .semantics(mergeDescendants = true) {
                contentDescription = announcement
            }
    )
}
```

### 11.3 Responsive Layouts

```kotlin
@Composable
fun AdaptiveHomeScreen() {
    val windowSizeClass = calculateWindowSizeClass(activity = LocalContext.current as Activity)
    
    when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> {
            // Phone portrait: Single column
            CompactHomeLayout()
        }
        
        WindowWidthSizeClass.Medium -> {
            // Tablet portrait or phone landscape: Two columns
            MediumHomeLayout()
        }
        
        WindowWidthSizeClass.Expanded -> {
            // Tablet landscape: Navigation rail + content
            ExpandedHomeLayout()
        }
    }
}
```

---

## 12. Data Models

[Original data models from PRD, with additions...]

### 12.1 Database Schema Extensions

```kotlin
// Add search history
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val timestamp: Long,
    val resultCount: Int
)

// Add listening statistics
@Entity(tableName = "listening_stats")
data class ListeningStatsEntity(
    @PrimaryKey val date: String, // YYYY-MM-DD
    val totalPlayTimeMs: Long,
    val tracksPlayed: Int,
    val topArtistId: String?,
    val topGenre: String?
)

// Add queue state (for persistence)
@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey val id: Int = 1, // Always 1, singleton
    val currentTrackId: String?,
    val currentPosition: Long,
    val trackIds: List<String>,
    val shuffled: Boolean,
    val repeatMode: String
)
```

---

## 13. Development Roadmap

### 13.1 Phase 1: Core MVP (Weeks 1-4)

**Week 1-2: Foundation**
- [ ] Project setup with Gradle, dependencies
- [ ] MVVM architecture scaffolding
- [ ] NewPipe Extractor integration and testing
- [ ] Basic UI with Material 3 theming
- [ ] Navigation structure (Home, Search, Library, Settings)

**Week 3-4: Core Playback**
- [ ] ExoPlayer integration
- [ ] Media3 Session service
- [ ] Basic playback controls (play, pause, skip)
- [ ] Queue management
- [ ] Background playback
- [ ] Media notification

**Milestone**: Users can search and play music with basic controls

---

### 13.2 Phase 2: Enhanced Experience (Weeks 5-8)

**Week 5-6: Offline & Storage**
- [ ] Download manager with WorkManager
- [ ] File encryption implementation
- [ ] Download queue and progress tracking
- [ ] Storage management UI
- [ ] Downloaded tracks playback

**Week 7-8: User Library**
- [ ] Room database setup
- [ ] Playlist creation and management
- [ ] Liked tracks collection
- [ ] History tracking
- [ ] Search history

**Milestone**: Users can download music and create playlists

---

### 13.3 Phase 3: Polish & Advanced (Weeks 9-12)

**Week 9-10: Advanced Features**
- [ ] Smart queue algorithm
- [ ] Sleep timer
- [ ] Audio equalizer
- [ ] Playback speed control
- [ ] Skip silence detection

**Week 11-12: UX Polish**
- [ ] Animations and transitions
- [ ] Dynamic color theming
- [ ] Widget (home screen)
- [ ] Lock screen controls enhancement
- [ ] Accessibility improvements

**Milestone**: Feature-complete app with polished UX

---

### 13.4 Phase 4: Scale & Ecosystem (Weeks 13-16)

**Week 13-14: Integrations**
- [ ] Lyrics support (LRCLib integration)
- [ ] Chromecast/DLNA support
- [ ] Android Auto integration

**Week 15-16: Quality & Release**
- [ ] Performance optimization
- [ ] Battery usage optimization
- [ ] Comprehensive testing
- [ ] Bug fixes
- [ ] Documentation
- [ ] F-Droid submission

**Milestone**: Production-ready v1.0 release

---

### 13.5 Post-Launch Roadmap

**Q2 2026**:
- [ ] Wear OS companion app
- [ ] Tablet-optimized layouts
- [ ] Advanced statistics and insights
- [ ] Export/import playlists

**Q3 2026**:
- [ ] Collaborative playlists (local sharing)
- [ ] Advanced search (filters, sorting)
- [ ] Custom themes
- [ ] Plugin system for extensibility

**Q4 2026**:
- [ ] Desktop app (Kotlin Multiplatform)
- [ ] iOS app (if resources allow)
- [ ] SyncThing integration for cross-device sync

---

## 14. Success Metrics & KPIs

### 14.1 Technical Performance Metrics

| Metric | Target | Measurement Method | Priority |
|--------|--------|--------------------|----------|
| Cold start time | < 2s | Firebase Performance Monitoring | P0 |
| Audio start latency | < 500ms | Custom instrumentation | P0 |
| Search response time | < 1.5s | Custom timer | P0 |
| Frame rate (UI) | ≥ 60 FPS | Systrace, GPU profiling | P0 |
| Battery usage (1hr playback) | < 5% | Android Vitals | P0 |
| Memory usage (peak) | < 200 MB | Android Profiler | P1 |
| APK size | < 15 MB | Build output | P1 |
| Crash-free rate | > 99.5% | Crash analytics | P0 |
| ANR rate | < 0.1% | Android Vitals | P0 |

### 14.2 User Engagement Metrics

| Metric | Target (6mo) | Measurement | Priority |
|--------|-------------|-------------|----------|
| Daily Active Users (DAU) | 10,000 | Analytics | P0 |
| Monthly Active Users (MAU) | 100,000 | Analytics | P0 |
| DAU/MAU ratio | > 30% | Calculated | P1 |
| Average session duration | > 30 minutes | Analytics | P1 |
| Retention (Day 1) | > 60% | Cohort analysis | P0 |
| Retention (Day 7) | > 40% | Cohort analysis | P0 |
| Retention (Day 30) | > 25% | Cohort analysis | P1 |
| Tracks played per session | > 5 | Analytics | P2 |
| Playlists created per user | > 2 | Database query | P2 |
| Downloads per user | > 10 tracks | Database query | P2 |

### 14.3 Quality Metrics

| Metric | Target | Measurement | Priority |
|--------|--------|-------------|----------|
| App store rating | > 4.5 stars | F-Droid, GitHub | P0 |
| GitHub stars | > 5,000 (1 year) | GitHub API | P2 |
| Issues resolution time | < 7 days (avg) | GitHub Issues | P1 |
| Community contributors | > 20 (1 year) | GitHub | P2 |

### 14.4 Business/Impact Metrics

| Metric | Target | Measurement | Priority |
|--------|--------|-------------|----------|
| Total downloads | 100K (6mo) | F-Droid stats | P0 |
| Active installations | 50K (6mo) | Analytics | P0 |
| Open source contributions | 50 PRs (1 year) | GitHub | P2 |
| Documentation completeness | 100% | Manual review | P1 |
| Feature requests implemented | > 30% | GitHub Issues | P2 |

---

## 15. Dependencies & Risks

### 15.1 External Dependencies

| Dependency | Type | Risk Level | Mitigation |
|------------|------|------------|------------|
| **NewPipe Extractor** | Critical | HIGH | • Monitor for breaking changes<br>• Maintain fork if necessary<br>• Abstract behind interface<br>• Pin to stable versions |
| **YouTube** | Critical | HIGH | • No official API used<br>• Extractor handles changes<br>• Plan for alternative sources<br>• User communication strategy |
| **ExoPlayer/Media3** | Critical | MEDIUM | • Google-maintained, stable<br>• Well-documented<br>• Regular updates<br>• Large community |
| **Android OS APIs** | Critical | LOW | • Official APIs only<br>• Backward compatibility<br>• Min SDK 26 with desugaring |
| **F-Droid** | Distribution | LOW | • Submit early for review<br>• Follow guidelines strictly<br>• GitHub releases as backup |

### 15.2 Technical Risks

| Risk | Impact | Probability | Mitigation Strategy |
|------|--------|-------------|---------------------|
| **YouTube changes break extraction** | HIGH | MEDIUM | • Stay updated with NewPipe community<br>• Rapid patch deployment process<br>• User notification system<br>• Fallback to cached content |
| **Legal challenges** | HIGH | LOW | • Open source, educational purpose<br>• No proprietary code<br>• Clear disclaimers<br>• Legal counsel review |
| **Performance issues on low-end devices** | MEDIUM | MEDIUM | • Profile on budget devices<br>• Optimize memory usage<br>• Lazy loading<br>• Settings for reduced features |
| **Battery drain** | MEDIUM | MEDIUM | • Optimize background tasks<br>• Use JobScheduler wisely<br>• Release wake locks<br>• Battery optimization testing |
| **Storage management issues** | MEDIUM | LOW | • Clear storage warnings<br>• Auto-cleanup old cache<br>• User-configurable limits<br>• Compressed downloads |
| **Network failures** | MEDIUM | HIGH | • Robust retry logic<br>• Offline-first design<br>• Clear error messages<br>• Download resume capability |

### 15.3 Resource Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Limited developer capacity** | MEDIUM | • Phased approach<br>• MVP first<br>• Community contributions<br>• Clear documentation |
| **Design resources** | LOW | • Material 3 guidelines<br>• Template-based approach<br>• Community designers |
| **Testing coverage** | MEDIUM | • Automated testing priority<br>• Community testing program<br>• Beta releases |

### 15.4 Market Risks

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| **Competing apps improve** | LOW | HIGH | • Focus on unique value: privacy + design<br>• Rapid feature development<br>• Community engagement |
| **User privacy concerns** | LOW | LOW | • Transparent communication<br>• Open source code<br>• Privacy-first marketing |
| **Platform policy changes** | MEDIUM | LOW | • F-Droid as primary distribution<br>• GitHub releases<br>• Sideloading support |

---

## 16. Security & Privacy

### 16.1 Security Requirements

**Data Encryption**:
- All downloaded files encrypted with AES-256-GCM
- Encryption keys stored in Android KeyStore
- No plaintext sensitive data in logs or storage

**Network Security**:
```xml
<!-- network_security_config.xml -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">youtube.com</domain>
        <domain includeSubdomains="true">googlevideo.com</domain>
    </domain-config>
    
    <!-- Certificate pinning for critical domains -->
    <domain-config>
        <domain includeSubdomains="true">youtube.com</domain>
        <pin-set>
            <pin digest="SHA-256">base64EncodedPin1==</pin>
            <pin digest="SHA-256">base64EncodedPin2==</pin>
        </pin-set>
    </domain-config>
</network-security-config>
```

**Code Security**:
- ProGuard/R8 obfuscation enabled
- Remove all debug logs in release builds
- No API keys or secrets in code (only public APIs)
- Regular dependency vulnerability scanning

### 16.2 Privacy Principles

**Zero Data Collection**:
- No analytics or tracking SDKs
- No crash reporting to third parties
- No user accounts or authentication
- No personal information collected

**Local-Only Data**:
- All user data stored locally on device
- Search history: optional and deletable
- Playback history: optional and deletable
- No cloud sync (by design)

**Transparency**:
- Open source code (GPL v3)
- Clear privacy policy
- Permissions justification in-app

**Minimal Permissions**:
```xml
<!-- Only essential permissions -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

---

## 17. Testing Strategy

### 17.1 Unit Testing

**Coverage Target**: 80% for domain and data layers

```kotlin
// Example: Use Case Testing
class PlayTrackUseCaseTest {
    @Test
    fun `playTrack success - returns success with stream URL`() = runTest {
        // Given
        val track = createTestTrack()
        val expectedStream = AudioStream(/* ... */)
        coEvery { extractorService.getAudioStream(any()) } returns Result.success(expectedStream)
        
        // When
        val result = playTrackUseCase(track)
        
        // Then
        assertTrue(result.isSuccess)
        coVerify { playbackManager.playTrack(track) }
    }
    
    @Test
    fun `playTrack failure - returns error`() = runTest {
        // Given
        coEvery { extractorService.getAudioStream(any()) } returns 
            Result.failure(NetworkException())
        
        // When
        val result = playTrackUseCase(track)
        
        // Then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is NetworkException)
    }
}
```

### 17.2 Integration Testing

```kotlin
@RunWith(AndroidJUnit4::class)
class PlaybackIntegrationTest {
    @Test
    fun fullPlaybackFlow_downloadsAndPlays() {
        // Start with search
        searchForTrack("Test Song")
        
        // Select track
        clickTrack(0)
        
        // Verify playback starts
        verifyPlayerState(isPlaying = true)
        
        // Download track
        clickDownloadButton()
        
        // Verify download completes
        waitForDownload()
        
        // Disconnect network
        disableNetwork()
        
        // Replay track
        clickTrack(0)
        
        // Verify plays from local storage
        verifyPlayerState(isPlaying = true)
    }
}
```

### 17.3 UI Testing (Compose)

```kotlin
@Test
fun homeScreen_displaysQuickPicks() {
    composeTestRule.setContent {
        SonicTheme {
            HomeScreen()
        }
    }
    
    composeTestRule
        .onNodeWithText("Quick Picks")
        .assertIsDisplayed()
    
    composeTestRule
        .onAllNodesWithTag("quick_pick_item")
        .assertCountEquals(6)
}
```

### 17.4 Performance Testing

**Key Metrics to Test**:
- App launch time (cold/warm)
- Search latency
- Audio start latency
- Memory usage during playback
- Battery consumption

**Tools**:
- Android Profiler
- Macrobenchmark library
- Systrace
- Battery Historian

### 17.5 Device Testing Matrix

| Category | Devices |
|----------|---------|
| **Low-end** | Android 8.0, 2GB RAM, 720p |
| **Mid-range** | Android 11, 4GB RAM, 1080p |
| **High-end** | Android 14, 8GB RAM, 1440p |
| **Tablet** | Android 13, 10" screen |
| **Foldable** | Galaxy Fold simulation |

---

## 18. Compliance & Legal

### 18.1 Open Source Licensing

**License**: GNU General Public License v3.0 (GPL v3)

**Rationale**:
- Ensures code remains open and free
- Compatible with NewPipe Extractor (GPL v3)
- Protects against proprietary forks
- Strong copyleft provisions

**Dependencies License Compatibility**:
- NewPipe Extractor: GPL v3 ✓
- ExoPlayer: Apache 2.0 ✓ (GPL-compatible)
- AndroidX libraries: Apache 2.0 ✓
- Kotlin: Apache 2.0 ✓
- All major dependencies: GPL-compatible

### 18.2 Legal Disclaimers

**In-App Disclaimer** (First Launch):
```
Sonic Music is an open-source music player that provides 
access to YouTube content through public extraction methods.

IMPORTANT:
• For educational and personal use only
• Users are responsible for complying with YouTube's Terms of Service
• No affiliation with Google or YouTube
• Respect copyright laws in your jurisdiction
• Support artists by purchasing music when possible

By continuing, you acknowledge these terms.
```

**README Disclaimer**:
```markdown
## Legal

This application is provided for educational purposes only. 
Users must comply with:

- YouTube Terms of Service
- Local copyright laws
- Content creator rights

We do not host, store, or distribute any copyrighted content. 
All content is accessed through publicly available YouTube APIs 
and user-provided queries.
```

### 18.3 Privacy Policy

**Compliance**: GDPR, CCPA compliant (by design - no data collection)

**Privacy Policy Summary**:
```markdown
# Privacy Policy

## Data Collection
We collect ZERO personal data. Period.

## Data Storage
All data is stored locally on your device:
- Search history (optional, deletable)
- Playback history (optional, deletable)
- Playlists
- Downloaded files

## Data Sharing
We share ZERO data with anyone. No third parties, no servers.

## Your Rights
You can delete all app data at any time through:
- Settings → Clear app data
- Uninstalling the app

## Contact
For questions: [GitHub Issues]
```

### 18.4 Content Disclaimer

```
Sonic Music does not:
- Host any content
- Store content on remote servers
- Modify or redistribute copyrighted material
- Claim ownership of any accessed content

All content belongs to their respective copyright holders.
```

### 18.5 Trademark

**"Sonic Music"** - Non-trademarked, open-source project name
- No trademark registration planned
- Free for community use and forks
- Attribution requested but not required

---

## 19. Open Questions & Future Considerations

### 19.1 Open Questions

1. **Monetization** (if ever needed):
   - Q: Should we accept donations?
   - Q: OpenCollective vs GitHub Sponsors vs Liberapay?
   - Decision: TBD, community input needed

2. **Collaborative Features**:
   - Q: Local network playlist sharing?
   - Q: QR code share playlists?
   - Decision: Post v1.0 feature

3. **Podcast Support**:
   - Q: Should we support podcast playback from YouTube?
   - Q: Separate app or integrated?
   - Decision: Gather user feedback first

4. **Desktop Version**:
   - Q: Kotlin Multiplatform Desktop?
   - Q: Electron-based?
   - Decision: After mobile stability

5. **Alternative Sources**:
   - Q: Support SoundCloud, Bandcamp via extractors?
   - Q: Keep YouTube-only for v1.0?
   - Decision: YouTube-only for v1.0, evaluate later

### 19.2 Future Feature Considerations

**Short-term** (3-6 months):
- [ ] Advanced statistics dashboard
- [ ] Batch download management
- [ ] Custom equalizer presets
- [ ] Gesture controls
- [ ] Mood-based auto-playlists

**Medium-term** (6-12 months):
- [ ] Wear OS app
- [ ] Android Auto improvements
- [ ] Tablet-optimized UI
- [ ] Themes marketplace (community)
- [ ] Plugin system

**Long-term** (12+ months):
- [ ] Desktop application
- [ ] iOS app (if feasible)
- [ ] Local network sync (no cloud)
- [ ] Advanced audio effects
- [ ] Visualizer

### 19.3 Technical Debt Monitoring

Items to track and address:
- [ ] Migrate to Kotlin 2.0 when stable
- [ ] Adopt Compose multiplatform
- [ ] Room schema migrations strategy
- [ ] Dependency updates schedule
- [ ] Code coverage improvements

---

## 20. Appendices

### 20.1 Glossary

| Term | Definition |
|------|------------|
| **ANR** | Application Not Responding - Android error when app blocks UI thread |
| **DXA** | Device-independent Pixels multiplied by 20 - Word measurement unit |
| **Extractor** | NewPipe component that fetches YouTube content |
| **Material 3** | Google's latest design system (Material You) |
| **MVVM** | Model-View-ViewModel architectural pattern |
| **Media3** | AndroidX media library (successor to ExoPlayer) |
| **F-Droid** | Open-source Android app repository |
| **GPL v3** | GNU General Public License version 3 |

### 20.2 References

**Technical Documentation**:
- [NewPipe Extractor Docs](https://teamnewpipe.github.io/documentation/)
- [Media3 Documentation](https://developer.android.com/guide/topics/media/media3)
- [Material 3 Guidelines](https://m3.material.io/)
- [Jetpack Compose Docs](https://developer.android.com/jetpack/compose)

**Design Resources**:
- [Material Design Icons](https://fonts.google.com/icons)
- [Pixel Design Patterns](https://material.io/blog/m3-expressive)
- [Android Accessibility](https://developer.android.com/guide/topics/ui/accessibility)

**Community**:
- GitHub: (TBD)
- Matrix/Discord: (TBD)
- Reddit: r/Sonic_Music (TBD)

### 20.3 Approval Signatures

| Role | Name | Signature | Date |
|------|------|-----------|------|
| Product Owner | __________ | __________ | ______ |
| Engineering Lead | __________ | __________ | ______ |
| UX/UI Lead | __________ | __________ | ______ |
| QA Lead | __________ | __________ | ______ |
| Legal Counsel | __________ | __________ | ______ |

---

**Document Status**: DRAFT - Awaiting Stakeholder Review

**Next Review Date**: [TBD]

**Version Control**: This document is maintained in version control. See Git history for detailed change tracking.

---

*End of Product Requirements Document*