# Product Requirements Document (PRD)

## SonicMusic - Android Music Streaming App

**Version:** 1.0  
**Last Updated:** February 5, 2026  
**Document Owner:** Product Team  
**Target Platform:** Android 14–16  

---

## 1. Executive Summary

### 1.1 Product Overview
SonicMusic is a modern Android music streaming application that provides YouTube Music-like functionality with a clean, Material You design. The app focuses exclusively on song-based streaming, filtering out albums, playlists, and compilation content to deliver a pure music listening experience.

### 1.2 Product Vision
To create the most elegant and intuitive music streaming experience on Android, leveraging Material 3 design principles and dynamic theming while providing seamless background playback and intelligent music discovery.

### 1.3 Success Metrics
- User retention rate > 60% (30-day)
- Average session duration > 20 minutes
- Daily active users growth rate > 10% month-over-month
- App crash rate < 0.5%
- Audio playback success rate > 98%

### 1.4 Target Audience & Personas
- The Focused Listener: wants fast access to individual songs without albums or playlist clutter.
- The Collector: likes saving songs, building playlists, and organizing a personal library.
- The Offline Commuter: downloads songs for low-connectivity listening.
- The Local Mixer: combines local device music with streaming in one player.

### 1.5 Core User Journeys
- Discover a song from Home and start playback within 2 taps.
- Search for a specific track and immediately play the top result.
- Save a song to Liked Songs and add it to a playlist.
- Download a song for offline use and play it later without connectivity.

### 1.6 MVP Scope & Non-Goals
**In Scope (v1.0):**
- Song-only streaming and search
- Liked songs, playlists, and history
- Background playback with mini/full player
- Offline downloads and local music scan
- Material 3 UI with dynamic theming

**Out of Scope (v1.x):**
- Podcasts and long-form audio
- Social features and sharing feeds
- Cross-device cloud sync
- Music videos and full album browsing

### 1.7 Assumptions & Constraints
- Availability of YouTubei metadata and NewPipe extractor for song streams.
- No user accounts or server-side user profiles in v1.0.
- Privacy-first design with local-only storage by default.
- Must comply with Android background playback and notification policies.

---

## 2. Technical Stack

### 2.1 Core Technologies

| Component | Technology | Purpose |
|-----------|------------|---------|
| **Language** | Kotlin | Primary development language |
| **UI Framework** | Jetpack Compose | Declarative UI |
| **Design System** | Material 3 (Material You) | Design language |
| **Audio Playback** | Media3 + ExoPlayer | Audio streaming & playback |
| **Audio Extraction** | NewPipe Extractor | Audio-only stream extraction |
| **Search & Metadata** | YouTubei (InnerTube API) | Search and metadata retrieval |
| **Background Playback** | Media3 MediaSessionService | Background audio & notifications |
| **Architecture** | MVVM + Clean Architecture | Code organization |
| **Dependency Injection** | Hilt | DI framework |
| **Database** | Room | Local data persistence |
| **Networking** | Retrofit + OkHttp | API communication |
| **Image Loading** | Coil | Async image loading |
| **Coroutines** | Kotlin Coroutines + Flow | Asynchronous operations |

### 2.2 Architecture Layers

```
┌─────────────────────────────────────┐
│         Presentation Layer          │
│   (Compose UI + ViewModels)         │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│          Domain Layer                │
│   (Use Cases + Domain Models)        │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│           Data Layer                 │
│ (Repositories + Data Sources)        │
│  - Remote (YouTubei, NewPipe)        │
│  - Local (Room Database)             │
│  - Cache (Memory + Disk)             │
└─────────────────────────────────────┘
```

---

## 3. App Architecture

### 3.1 Navigation Structure

```
SonicMusic App
│
├── Bottom Navigation
│   ├── Home
│   ├── Search
│   ├── Library
│   └── Settings
│
├── Persistent Components
│   └── Mini Player (visible across all screens)
│
└── Modal Screens
    └── Full Player (expands from Mini Player)
```

### 3.2 Data Flow

```
UI Layer (Compose)
    ↓ User Action
ViewModel
    ↓ Request
Use Case
    ↓ Business Logic
Repository
    ↓ Data Request
┌───────────┴───────────┐
│                       │
Remote Data Source   Local Data Source
(NewPipe, YouTubei)  (Room DB, Cache)
```

---

## 4. Detailed Feature Specifications

## 4.1 Home Page

### 4.1.1 Overview
The Home page serves as the primary discovery interface, presenting personalized and trending content in a scrollable feed format.

### 4.1.2 UI Layout

```
┌─────────────────────────────────────┐
│  [SonicMusic Logo]    [Profile Icon]│
├─────────────────────────────────────┤
│                                     │
│  Listen Again                    →  │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐      │
│  │ 🎵 │ │ 🎵 │ │ 🎵 │ │ 🎵 │      │
│  └────┘ └────┘ └────┘ └────┘      │
│                                     │
│  Quick Picks                     →  │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐      │
│  │ 🎵 │ │ 🎵 │ │ 🎵 │ │ 🎵 │      │
│  └────┘ └────┘ └────┘ └────┘      │
│                                     │
│  Forgotten Favorites             →  │
│  [Horizontal scrollable list]       │
│                                     │
│  New Releases                    →  │
│  [Horizontal scrollable list]       │
│                                     │
│  Trending Songs                  →  │
│  [Horizontal scrollable list]       │
│                                     │
│  English Hits                    →  │
│  [Horizontal scrollable list]       │
│                                     │
│  Artists                         →  │
│  [Songs by popular artists]         │
│                                     │
├─────────────────────────────────────┤
│        [Mini Player]                │
└─────────────────────────────────────┘
```

### 4.1.3 Content Sections

#### Listen Again
- **Purpose:** Quick access to recently played songs
- **Data Source:** Local playback history
- **Display:** Horizontal scrollable list
- **Item Count:** 10-15 songs
- **Sorting:** Most recent first
- **Update Frequency:** Real-time

**Item Card:**
```
┌──────────────┐
│  ┌────────┐  │
│  │        │  │ Album Art (56dp × 56dp)
│  │ Image  │  │
│  └────────┘  │
│  Song Title  │ (1 line, ellipsis)
│  Artist Name │ (1 line, ellipsis)
└──────────────┘
```

#### Quick Picks
- **Purpose:** Personalized song recommendations
- **Data Source:** Algorithm based on listening history + trending
- **Display:** Horizontal scrollable list
- **Item Count:** 15-20 songs
- **Refresh:** Every 6 hours or on manual pull-to-refresh
- **Filtering:** 
  - Songs only (no albums, playlists, DJ mixes)
  - Exclude previously listened songs from last 7 days
  - Match user's preferred genres/artists

#### Forgotten Favorites
- **Purpose:** Surface songs user enjoyed but hasn't played recently
- **Data Source:** Local liked songs + history
- **Display:** Horizontal scrollable list
- **Item Count:** 10-15 songs
- **Criteria:** 
  - Liked or played 5+ times
  - Not played in last 30 days
  - Sorted by previous play count

#### New Releases
- **Purpose:** Latest song releases
- **Data Source:** YouTubei API
- **Display:** Horizontal scrollable list
- **Item Count:** 20-25 songs
- **Filtering:**
  - Songs released in last 14 days
  - Exclude: albums, compilations, "Best of" collections
  - Filter keywords: "DJ", "Mix", "Mashup", "Album", "Compilation"
- **Update:** Daily at 00:00 UTC

#### Trending Songs
- **Purpose:** Currently popular songs
- **Data Source:** YouTubei trending/charts API
- **Display:** Horizontal scrollable list
- **Item Count:** 20-30 songs
- **Refresh:** Every 3 hours
- **Filtering:**
  - Strict song-only filter
  - Exclude DJ sets, remixes labeled as mixes
  - Video duration: 1:30 - 8:00 minutes (typical song range)

#### English Hits
- **Purpose:** Popular English language songs
- **Data Source:** YouTubei charts filtered by language
- **Display:** Horizontal scrollable list
- **Item Count:** 20-25 songs
- **Filtering:**
  - Language detection on metadata
  - Songs only
  - Exclude non-English content

#### Artists Section
- **Purpose:** Songs from popular artists
- **Data Source:** User listening patterns + global popularity
- **Display:** Vertical list of artist rows, each with horizontal song list
- **Layout:**
```
Artist Name                          →
┌────┐ ┌────┐ ┌────┐ ┌────┐
│ 🎵 │ │ 🎵 │ │ 🎵 │ │ 🎵 │
└────┘ └────┘ └────┘ └────┘
```
- **Artist Count:** 5-8 artists
- **Songs per Artist:** 8-10 songs
- **Selection:** Based on user's top played artists

### 4.1.4 Interaction Behaviors

**Pull-to-Refresh:**
- Refreshes all sections simultaneously
- Shows Material 3 circular progress indicator
- Animations: Smooth bounce effect
- Haptic feedback on refresh trigger

**Song Card Tap:**
- Action: Start playback immediately
- Animation: Ripple effect on card
- Transition: Mini player slides up from bottom
- Queue behavior: Replace queue with section songs

**Song Card Long Press:**
- Shows bottom sheet with options:
  - Play next
  - Add to queue
  - Add to playlist
  - Like/Unlike
  - Share
  - Go to artist
  - Download (cache)

**Section Header Arrow Tap:**
- Navigates to dedicated section view
- Shows all items in grid layout
- Maintains scroll position on back navigation

### 4.1.5 Loading States

**Initial Load:**
```
┌─────────────────────────┐
│  [Shimmer placeholder]  │
│  ┌──┐ ┌──┐ ┌──┐ ┌──┐   │
│  │  │ │  │ │  │ │  │   │
│  └──┘ └──┘ └──┘ └──┘   │
└─────────────────────────┘
```

**Error State:**
```
┌─────────────────────────┐
│    ⚠️ Unable to load    │
│   [Retry Button]        │
└─────────────────────────┘
```

**Empty State:**
```
┌─────────────────────────┐
│    🎵 No songs yet      │
│   Start exploring!      │
└─────────────────────────┘
```

### 4.1.6 Performance Requirements

- **Time to First Paint:** < 500ms
- **Section Load Time:** < 1.5s per section
- **Scroll Performance:** 60 FPS (no jank)
- **Image Loading:** Progressive with placeholder
- **Cache Strategy:** 
  - Memory cache: 50 thumbnails
  - Disk cache: 200 thumbnails, 7-day TTL

---

## 4.2 Search Page

### 4.2.1 Overview
YouTube Music-style search interface with real-time suggestions and song-only results.

### 4.2.2 UI Layout

**Default State (No Query):**
```
┌─────────────────────────────────────┐
│  [🔍 Search songs...]     [X]       │
├─────────────────────────────────────┤
│                                     │
│  Recent Searches                    │
│  • Song Name 1                      │
│  • Song Name 2                      │
│  • Artist Name                      │
│                                     │
│  Trending Searches                  │
│  1. Trending Song 1                 │
│  2. Trending Song 2                 │
│  3. Trending Song 3                 │
│                                     │
└─────────────────────────────────────┘
```

**Active Typing State:**
```
┌─────────────────────────────────────┐
│  [🔍 blinding li...]      [X]       │
├─────────────────────────────────────┤
│                                     │
│  Suggestions                        │
│  🔍 blinding lights                 │
│  🔍 blinding lights the weeknd      │
│  🔍 blinding lights remix           │
│                                     │
└─────────────────────────────────────┘
```

**Results State:**
```
┌─────────────────────────────────────┐
│  [🔍 blinding lights]     [X]       │
├─────────────────────────────────────┤
│  Songs                              │
│                                     │
│  ┌────┐  Blinding Lights      3:20 │
│  │ 🎵 │  The Weeknd           [⋮]  │
│  └────┘                             │
│                                     │
│  ┌────┐  Blinding Lights      3:45 │
│  │ 🎵 │  The Weeknd (Remix)   [⋮]  │
│  └────┘                             │
│                                     │
│  [Load More]                        │
│                                     │
├─────────────────────────────────────┤
│        [Mini Player]                │
└─────────────────────────────────────┘
```

### 4.2.3 Search Functionality

#### Search Input
- **Component:** OutlinedTextField with Material 3 styling
- **Placeholder:** "Search songs..."
- **Icon:** Search icon (left), Clear icon (right, when text present)
- **Behavior:**
  - Auto-focus on page open
  - Debounce: 300ms before triggering search
  - Voice input support (microphone icon)
  - Keyboard type: Text with suggestions

#### Search Suggestions
- **Trigger:** After 2 characters typed
- **Source:** 
  - Local: Recent searches (top 5)
  - Remote: YouTubei autocomplete API
- **Display:** Dropdown list below search bar
- **Max Results:** 8 suggestions
- **Update:** Real-time as user types
- **Interaction:** Tap to execute search

#### Search Execution
- **Trigger:** 
  - Enter key press
  - Suggestion tap
  - Search icon tap
- **Action:** 
  1. Add query to recent searches
  2. Show loading indicator
  3. Fetch results from YouTubei
  4. Apply song-only filters
  5. Display results

### 4.2.4 Result Filtering

**Strict Song-Only Filter:**

```kotlin
fun isValidSong(item: SearchResult): Boolean {
    // Title/description exclusion patterns
    val excludePatterns = listOf(
        "dj mix", "dj set", "mix tape",
        "album", "ep", "full album",
        "mashup", "mega mix",
        "best of", "greatest hits",
        "compilation", "collection",
        "live set", "radio edit",
        "podcast", "interview"
    )
    
    val titleLower = item.title.toLowerCase()
    val descLower = item.description?.toLowerCase() ?: ""
    
    // Check exclusions
    if (excludePatterns.any { titleLower.contains(it) || descLower.contains(it) }) {
        return false
    }
    
    // Duration check (1:30 - 8:00 typical song range)
    if (item.duration < 90 || item.duration > 480) {
        return false
    }
    
    // Category check
    if (item.category != "Music") {
        return false
    }
    
    // Artist check (should have clear artist attribution)
    if (item.artist.isNullOrBlank()) {
        return false
    }
    
    return true
}
```

### 4.2.5 Result Display

**Song Item Layout:**
```
┌────────────────────────────────────────┐
│  ┌────┐  Song Title               3:42│
│  │    │  Artist Name              [⋮] │
│  │ 🎵 │  Album • Year                 │
│  └────┘                                │
└────────────────────────────────────────┘
```

**Item Components:**
- **Thumbnail:** 56dp × 56dp, rounded corners (4dp)
- **Title:** 16sp, Medium weight, 1-2 lines max
- **Artist:** 14sp, Regular weight, 1 line, secondary color
- **Metadata:** 12sp, Regular weight, tertiary color
- **Duration:** 14sp, Regular weight, right-aligned
- **Menu:** Three-dot icon for options

**Metadata Display:**
- Format: `Album Name • Year`
- If album unknown: Show view count or play count
- Truncate with ellipsis if too long

### 4.2.6 Interaction Behaviors

**Song Item Tap:**
- Play song immediately
- Replace queue with search results
- Mini player slides up
- Haptic feedback (light)

**Song Item Long Press:**
- Vibrate (medium)
- Show bottom sheet:
  - Play next
  - Add to queue
  - Add to playlist
  - Like
  - Share
  - Go to artist
  - Download

**Menu Icon Tap:**
- Show bottom sheet (same as long press)

**Recent Search Item Tap:**
- Populate search field
- Execute search

**Recent Search Delete (Swipe Left):**
- Swipe-to-delete gesture
- Show undo snackbar (5 seconds)
- Remove from local storage

### 4.2.7 Recent Searches

**Storage:**
- Local Room database
- Table: `recent_searches`
- Fields: `query`, `timestamp`
- Max entries: 20 (FIFO)

**Display:**
- Show top 5 most recent
- Sort by timestamp descending
- Format: Plain text list

### 4.2.8 Trending Searches

**Data Source:**
- YouTubei trending queries
- Filtered for music-related terms
- Updated every 6 hours

**Display:**
- Numbered list (1-10)
- Show first 5 by default
- "Show more" expands to 10

### 4.2.9 Pagination

**Initial Load:** 20 results

**Load More:**
- Trigger: User scrolls to bottom
- Load: Next 20 results
- Indicator: Circular progress at bottom
- Max results: 100 (performance limit)

### 4.2.10 Empty States

**No Results:**
```
┌─────────────────────────┐
│     🔍 No songs found   │
│   Try a different query │
└─────────────────────────┘
```

**No Recent Searches:**
```
┌─────────────────────────┐
│  Your recent searches   │
│    will appear here     │
└─────────────────────────┘
```

### 4.2.11 Error Handling

**Network Error:**
- Show error message with retry button
- Cache recent searches for offline browsing
- Retry button triggers new search

**API Rate Limit:**
- Show friendly error message
- Implement exponential backoff
- Cache results aggressively

### 4.2.12 Performance Requirements

- **Search Latency:** < 800ms
- **Suggestion Response:** < 300ms
- **Scroll Performance:** 60 FPS
- **Result Load Time:** < 1.5s for 20 items
- **Cache Hit Rate:** > 70% for repeated searches

---

## 4.3 Library Page

### 4.3.1 Overview
Centralized location for user's personal music collection, including liked songs, playlists, history, local files, and favorite artists.

### 4.3.2 UI Layout

```
┌─────────────────────────────────────┐
│  Library              [Search Icon] │
├─────────────────────────────────────┤
│                                     │
│  ┌─────────────────────────────┐   │
│  │  ❤️  Liked Songs            │   │
│  │  123 songs                  │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─────────────────────────────┐   │
│  │  📂  Playlists              │   │
│  │  5 playlists                │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─────────────────────────────┐   │
│  │  🕐  Recently Played        │   │
│  │  Last 100 songs             │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─────────────────────────────┐   │
│  │  📱  Local Songs            │   │
│  │  45 songs                   │   │
│  └─────────────────────────────┘   │
│                                     │
│  ┌─────────────────────────────┐   │
│  │  👤  Artists                │   │
│  │  Based on your listening    │   │
│  └─────────────────────────────┘   │
│                                     │
├─────────────────────────────────────┤
│        [Mini Player]                │
└─────────────────────────────────────┘
```

### 4.3.3 Section Details

#### Liked Songs

**UI Component:**
```
┌────────────────────────────────────┐
│  ┌────┐                            │
│  │ ❤️ │  Liked Songs               │
│  └────┘  123 songs            →   │
└────────────────────────────────────┘
```

**Functionality:**
- **Storage:** Local Room database
- **Tap Action:** Navigate to Liked Songs detail view
- **Detail View Layout:**
```
┌─────────────────────────────────────┐
│  [←] Liked Songs    [Sort] [Search] │
├─────────────────────────────────────┤
│                                     │
│  ┌────┐  Song Title 1          3:42│
│  │ 🎵 │  Artist Name          [❤️] │
│  └────┘                             │
│                                     │
│  ┌────┐  Song Title 2          4:15│
│  │ 🎵 │  Artist Name          [❤️] │
│  └────┘                             │
│                                     │
│  [Play All] [Shuffle All]           │
│                                     │
└─────────────────────────────────────┘
```

**Features:**
- **Sort Options:**
  - Recently added (default)
  - Title (A-Z)
  - Artist (A-Z)
  - Duration
- **Bulk Actions:**
  - Play all
  - Shuffle all
  - Add all to queue
  - Export to playlist
- **Search:** Filter liked songs by title/artist
- **Sync:** Cloud sync support (future feature)

**Data Model:**
```kotlin
@Entity(tableName = "liked_songs")
data class LikedSong(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val duration: Int,
    val thumbnailUrl: String,
    val likedAt: Long,
    val streamUrl: String?,
    val albumName: String?
)
```

#### Playlists

**UI Component:**
```
┌────────────────────────────────────┐
│  ┌────┐                            │
│  │ 📂 │  Playlists                 │
│  └────┘  5 playlists          →   │
└────────────────────────────────────┘
```

**Functionality:**
- **Tap Action:** Navigate to Playlists list view
- **List View:**
```
┌─────────────────────────────────────┐
│  [←] Playlists        [+ Create]    │
├─────────────────────────────────────┤
│                                     │
│  ┌────────┐  Workout Mix       25  │
│  │ Cover  │  Songs            [⋮]  │
│  └────────┘                         │
│                                     │
│  ┌────────┐  Chill Vibes       18  │
│  │ Cover  │  Songs            [⋮]  │
│  └────────┘                         │
│                                     │
└─────────────────────────────────────┘
```

**Create Playlist Flow:**
1. Tap "+ Create" button
2. Show dialog:
```
┌──────────────────────┐
│  Create Playlist     │
├──────────────────────┤
│  [Playlist Name]     │
│  [Description]       │
│                      │
│  [Cancel]  [Create]  │
└──────────────────────┘
```
3. Validate name (required, max 100 chars)
4. Create playlist in database
5. Navigate to empty playlist detail view

**Playlist Detail View:**
```
┌─────────────────────────────────────┐
│  [←] Workout Mix       [Edit] [⋮]   │
│  25 songs • 1h 45m                  │
├─────────────────────────────────────┤
│  [Play All] [Shuffle] [+ Add Songs] │
│                                     │
│  ┌────┐  Song Title 1          3:42│
│  │ 🎵 │  Artist Name          [⋮]  │
│  └────┘                             │
│                                     │
│  [Drag handle to reorder]           │
│                                     │
└─────────────────────────────────────┘
```

**Playlist Features:**
- **Add Songs:** Search and add from entire catalog
- **Reorder:** Drag-and-drop to reorder songs
- **Remove:** Swipe left to remove from playlist
- **Edit:** Change name, description, cover art
- **Delete:** Delete entire playlist with confirmation
- **Share:** Generate shareable link (future)

**Data Model:**
```kotlin
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String?,
    val coverArtUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val songCount: Int
)

@Entity(
    tableName = "playlist_songs",
    foreignKeys = [ForeignKey(
        entity = Playlist::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class PlaylistSong(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: String,
    val position: Int,
    val addedAt: Long
)
```

#### Recently Played

**UI Component:**
```
┌────────────────────────────────────┐
│  ┌────┐                            │
│  │ 🕐 │  Recently Played           │
│  └────┘  Last 100 songs       →   │
└────────────────────────────────────┘
```

**Functionality:**
- **Storage:** Local Room database with automatic pruning
- **Limit:** Last 100 songs
- **Tap Action:** Navigate to history view

**Detail View:**
```
┌─────────────────────────────────────┐
│  [←] Recently Played  [Clear All]   │
├─────────────────────────────────────┤
│                                     │
│  Today                              │
│  ┌────┐  Song Title 1          3:42│
│  │ 🎵 │  Artist • 2 hours ago      │
│  └────┘                             │
│                                     │
│  Yesterday                          │
│  ┌────┐  Song Title 2          4:15│
│  │ 🎵 │  Artist • Yesterday         │
│  └────┘                             │
│                                     │
│  This Week                          │
│  [More songs grouped by date]       │
│                                     │
└─────────────────────────────────────┘
```

**Features:**
- **Grouping:** By date (Today, Yesterday, This Week, Earlier)
- **Timestamp:** Relative time display
- **Clear History:** Option to clear all or by date range
- **Privacy:** Option to pause history recording

**Data Model:**
```kotlin
@Entity(tableName = "playback_history")
data class PlaybackHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val playedAt: Long,
    val playDuration: Int, // How long the song was played
    val completed: Boolean // Did user play to the end
)
```

**Auto-Pruning:**
- Keep only last 100 entries
- Run cleanup on app startup and after each playback
- Option to export history before clearing

#### Local Songs

**UI Component:**
```
┌────────────────────────────────────┐
│  ┌────┐                            │
│  │ 📱 │  Local Songs               │
│  └────┘  45 songs             →   │
└────────────────────────────────────┘
```

**Functionality:**
- **Source:** Device storage (Music, Downloads folders)
- **Scan:** Automatic scan on app startup
- **Permissions:** READ_MEDIA_AUDIO (Android 13+) or READ_EXTERNAL_STORAGE
- **Supported Formats:** MP3, M4A, FLAC, OGG, WAV

**Detail View:**
```
┌─────────────────────────────────────┐
│  [←] Local Songs      [Scan] [Sort] │
├─────────────────────────────────────┤
│                                     │
│  ┌────┐  Local Song 1          3:42│
│  │ 🎵 │  Artist Name          [⋮]  │
│  │    │  /Music/song.mp3            │
│  └────┘                             │
│                                     │
│  [Play All] [Shuffle All]           │
│                                     │
└─────────────────────────────────────┘
```

**Features:**
- **Metadata Reading:** Extract ID3 tags (title, artist, album, artwork)
- **Manual Scan:** Refresh button to scan for new files
- **Folder Selection:** Choose specific folders to scan
- **Exclusions:** Exclude notification sounds, ringtones
- **Sort Options:** 
  - Title
  - Artist
  - Album
  - Date added
  - File size

**Scanner Implementation:**
```kotlin
class LocalMusicScanner(context: Context) {
    suspend fun scanDeviceMusic(): List<LocalSong> {
        val songs = mutableListOf<LocalSong>()
        
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID
        )
        
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { cursor ->
            // Extract song data
        }
        
        return songs
    }
}
```

**Data Model:**
```kotlin
@Entity(tableName = "local_songs")
data class LocalSong(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val duration: Int,
    val filePath: String,
    val albumId: Long?,
    val addedAt: Long,
    val fileSize: Long
)
```

#### Artists

**UI Component:**
```
┌────────────────────────────────────┐
│  ┌────┐                            │
│  │ 👤 │  Artists                   │
│  └────┘  Based on listening   →   │
└────────────────────────────────────┘
```

**Functionality:**
- **Source:** Aggregated from listening history
- **Criteria:** Artists with 5+ plays
- **Tap Action:** Navigate to artists grid view

**Grid View:**
```
┌─────────────────────────────────────┐
│  [←] Artists            [Sort]      │
├─────────────────────────────────────┤
│                                     │
│  ┌────────┐  ┌────────┐            │
│  │ Artist │  │ Artist │            │
│  │  Photo │  │  Photo │            │
│  └────────┘  └────────┘            │
│  Artist 1    Artist 2               │
│  15 songs    23 songs               │
│                                     │
│  [Grid continues...]                │
│                                     │
└─────────────────────────────────────┘
```

**Sort Options:**
- Most played (default)
- Recently played
- Alphabetical
- Number of songs

**Artist Detail View (Future):**
- Top songs by artist
- Similar artists
- Artist bio/info
- Discography (optional)

**Data Model:**
```kotlin
data class ArtistWithStats(
    val artistName: String,
    val playCount: Int,
    val songCount: Int,
    val lastPlayedAt: Long,
    val topSongIds: List<String>
)

// Generated from playback history via query
```

### 4.3.4 Library Search

**Trigger:** Tap search icon in top bar

**Scope:** Search across:
- Liked songs
- Playlist names and songs
- Recently played
- Local songs
- Artist names

**Results Display:**
```
┌─────────────────────────────────────┐
│  [🔍 Search library...]      [X]    │
├─────────────────────────────────────┤
│                                     │
│  Liked Songs (3)                    │
│  ┌────┐  Song Title               │
│  │ 🎵 │  Artist           [❤️]    │
│  └────┘                             │
│                                     │
│  Playlists (1)                      │
│  📂  Workout Mix                    │
│                                     │
│  Local Songs (2)                    │
│  [Local song results]               │
│                                     │
└─────────────────────────────────────┘
```

### 4.3.5 Performance Requirements

- **Library Load Time:** < 800ms
- **Playlist Detail Load:** < 500ms
- **Local Music Scan:** < 5s for 1000 songs
- **Search Response:** < 300ms
- **Database Query Optimization:** Indexed on frequently queried fields

---

## 4.4 Settings Page

### 4.4.1 Overview
Comprehensive settings for app customization, audio quality, playback behavior, storage management, and privacy controls.

### 4.4.2 UI Layout

```
┌─────────────────────────────────────┐
│  Settings                           │
├─────────────────────────────────────┤
│                                     │
│  🎵 Audio Quality                   │
│  ───────────────────────            │
│  Streaming Quality          High  → │
│  Download Quality          Best   → │
│  Normalize Volume          [✓]     │
│  Equalizer                       → │
│                                     │
│  ▶️ Playback                        │
│  ───────────────────────            │
│  Background Playback       [✓]     │
│  Resume on Connect         [✓]     │
│  Gapless Playback          [✓]     │
│  Crossfade Duration        3s    → │
│  Skip Silence              [✓]     │
│  Auto-Queue Similar        [ ]     │
│                                     │
│  🎨 Appearance                      │
│  ───────────────────────            │
│  Theme                     System → │
│  Dynamic Colors            [✓]     │
│  Album Art Blur            [✓]     │
│  Grid Layout               3 cols → │
│                                     │
│  💾 Storage & Cache                 │
│  ───────────────────────            │
│  Cache Location            Internal→│
│  Cache Size Limit          2 GB   → │
│  Clear Cache               [Clear]  │
│  Downloaded Songs          1.2 GB   │
│  Manage Downloads                → │
│                                     │
│  🔒 Privacy & Permissions           │
│  ───────────────────────            │
│  Pause History             [ ]     │
│  Clear Search History      [Clear]  │
│  App Permissions                 → │
│                                     │
│  ℹ️ About                           │
│  ───────────────────────            │
│  App Version               1.0.0    │
│  Check for Updates         [Check]  │
│  Licenses                        → │
│  Privacy Policy                  → │
│  Terms of Service                → │
│  Send Feedback                   → │
│                                     │
└─────────────────────────────────────┘
```

### 4.4.3 Setting Categories

#### Audio Quality

**Streaming Quality**
- **Options:** Low (64 kbps), Medium (128 kbps), High (192 kbps), Best (256 kbps)
- **Default:** High
- **WiFi Override:** Option to always use Best on WiFi
- **Implementation:**
```kotlin
enum class StreamQuality(val bitrate: Int) {
    LOW(64),
    MEDIUM(128),
    HIGH(192),
    BEST(256)
}

fun getStreamUrl(songId: String, quality: StreamQuality): String {
    // NewPipe Extractor quality selection
    return newPipeExtractor.getAudioStream(songId, quality.bitrate)
}
```

**Download Quality**
- **Options:** Medium (128 kbps), High (192 kbps), Best (256 kbps)
- **Default:** Best
- **Note:** Downloads are for offline playback cache

**Normalize Volume**
- **Type:** Toggle switch
- **Default:** Enabled
- **Function:** Apply ReplayGain or volume normalization across tracks
- **Implementation:** ExoPlayer AudioProcessor

**Equalizer**
- **Type:** Navigation to EQ screen
- **Presets:** Rock, Pop, Jazz, Classical, Bass Boost, Treble Boost, Custom
- **Bands:** 5-band equalizer (60Hz, 230Hz, 910Hz, 4kHz, 14kHz)
- **UI:**
```
┌─────────────────────────────────────┐
│  [←] Equalizer          [Reset]     │
├─────────────────────────────────────┤
│                                     │
│  Preset: [Rock ▼]                   │
│                                     │
│  60Hz   230Hz  910Hz  4kHz  14kHz   │
│  │       │      │      │      │     │
│  ├───────┼──────┼──────┼──────┤     │
│  │   ●   │      │      │      │     │
│  │       │  ●   │      │      │     │
│  │       │      │  ●   │      │     │
│  │       │      │      │  ●   │     │
│  │       │      │      │      │  ●  │
│  ├───────┼──────┼──────┼──────┤     │
│                                     │
│  Bass Boost:  [────●────]  50%     │
│  Virtualizer: [──●──────]  30%     │
│                                     │
└─────────────────────────────────────┘
```

#### Playback Settings

**Background Playback**
- **Type:** Toggle
- **Default:** Enabled
- **Function:** Continue playback when app is backgrounded
- **Note:** Always enabled (core feature)

**Resume on Connect**
- **Type:** Toggle
- **Default:** Enabled
- **Function:** Auto-resume when headphones/Bluetooth connects
- **Condition:** Only if paused due to disconnect

**Gapless Playback**
- **Type:** Toggle
- **Default:** Enabled
- **Function:** Seamless transition between tracks
- **Implementation:** ExoPlayer gapless mode

**Crossfade Duration**
- **Type:** Slider (0s - 12s)
- **Default:** 3s
- **Step:** 1s
- **Note:** Disabled when gapless is enabled

**Skip Silence**
- **Type:** Toggle
- **Default:** Enabled
- **Function:** Auto-skip silent portions in tracks
- **Threshold:** > 1.5s of silence

**Auto-Queue Similar Songs**
- **Type:** Toggle
- **Default:** Disabled
- **Function:** When queue ends, add similar songs
- **Source:** Recommendation algorithm

#### Appearance

**Theme**
- **Options:** Light, Dark, System (follows system theme)
- **Default:** System
- **Implementation:** Material 3 dynamic theming

**Dynamic Colors**
- **Type:** Toggle (Android 12+ only)
- **Default:** Enabled
- **Function:** Extract colors from album art for UI
- **Fallback:** Material You system colors

**Album Art Blur**
- **Type:** Toggle
- **Default:** Enabled
- **Function:** Blurred album art background in full player
- **Performance:** GPU-accelerated blur effect

**Grid Layout**
- **Options:** 2 columns, 3 columns, 4 columns
- **Default:** 3 columns
- **Applies to:** Song grids, album grids

#### Storage & Cache

**Cache Location**
- **Options:** Internal storage, External SD card (if available)
- **Default:** Internal
- **Permission:** Storage access required for external

**Cache Size Limit**
- **Type:** Slider (500 MB - 10 GB)
- **Default:** 2 GB
- **Function:** Max size for streaming cache
- **Auto-Cleanup:** LRU eviction when limit reached

**Clear Cache**
- **Type:** Action button
- **Confirmation:** "Clear X.XX GB of cached data?"
- **Function:** Delete all cached audio streams
- **Preserve:** Downloaded songs, thumbnails

**Downloaded Songs**
- **Display:** Total size of downloaded songs
- **Action:** Navigate to download manager

**Manage Downloads:**
```
┌─────────────────────────────────────┐
│  [←] Downloads          [Sort]      │
│  Using 1.2 GB                       │
├─────────────────────────────────────┤
│                                     │
│  ┌────┐  Song Title           45 MB│
│  │ ✓  │  Artist              [🗑️] │
│  └────┘                             │
│                                     │
│  [Select All] [Delete Selected]     │
│                                     │
└─────────────────────────────────────┘
```

#### Privacy & Permissions

**Pause History**
- **Type:** Toggle
- **Default:** Disabled
- **Function:** Temporarily stop recording playback history
- **Note:** Shows icon in UI when active

**Clear Search History**
- **Type:** Action button
- **Confirmation:** "Clear all search history?"
- **Function:** Delete all recent searches

**App Permissions:**
- Navigate to system permission settings
- Show status of:
  - Notification access (required for controls)
  - Storage access (for local songs)
  - Network access (always granted)

#### About

**App Version**
- Display current version (e.g., 1.0.0)
- Build number in debug builds

**Check for Updates**
- Check Google Play for updates
- Show "Up to date" or "Update available"

**Licenses**
- Open-source licenses screen
- List all dependencies and their licenses

**Privacy Policy & Terms**
- Open in-app WebView or external browser

**Send Feedback**
- Opens email client or feedback form
- Pre-populates device/app info

### 4.4.4 Data Persistence

All settings stored in:
```kotlin
// DataStore Preferences
object SettingsKeys {
    val STREAM_QUALITY = intPreferencesKey("stream_quality")
    val DOWNLOAD_QUALITY = intPreferencesKey("download_quality")
    val NORMALIZE_VOLUME = booleanPreferencesKey("normalize_volume")
    val GAPLESS_PLAYBACK = booleanPreferencesKey("gapless_playback")
    val CROSSFADE_DURATION = intPreferencesKey("crossfade_duration")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
    val CACHE_SIZE_LIMIT = longPreferencesKey("cache_size_limit")
    // ... more keys
}
```

### 4.4.5 Performance Considerations

- **Settings Load:** < 200ms
- **Settings Change:** Immediate UI update
- **Cache Clear:** Background thread, show progress
- **Large Lists:** Virtual scrolling for licenses

---

## 4.5 Mini Player

### 4.5.1 Overview
Persistent compact player visible across all app screens, providing quick access to playback controls without interrupting navigation.

### 4.5.2 UI Layout

```
┌─────────────────────────────────────┐
│  ┌────┐  Song Title                 │
│  │ 🎵 │  Artist Name    [❤️] [⏸️]  │
│  └────┘  ▓▓▓▓▓▓░░░░░░  60%         │
└─────────────────────────────────────┘
```

**Dimensions:**
- **Height:** 64dp
- **Elevation:** 4dp (Material 3 surface variant)
- **Corner Radius:** 12dp (top corners only)
- **Position:** Fixed bottom, above bottom navigation

**Components:**

1. **Album Art Thumbnail**
   - Size: 48dp × 48dp
   - Rounded corners: 4dp
   - Left padding: 8dp

2. **Song Info**
   - Title: 14sp, Medium weight, 1 line max, ellipsis
   - Artist: 12sp, Regular weight, 1 line max, secondary color
   - Vertical stack, centered

3. **Like Button**
   - Icon: Heart (filled if liked, outline if not)
   - Size: 24dp
   - Toggle on tap
   - Haptic feedback

4. **Play/Pause Button**
   - Icon: Play or Pause
   - Size: 24dp
   - Animated icon transition
   - Right padding: 12dp

5. **Progress Bar**
   - Height: 2dp
   - Position: Absolute bottom
   - Foreground: Primary color
   - Background: Surface variant
   - Updates every 250ms

### 4.5.3 Interaction Behaviors

**Tap (Main Area):**
- Action: Expand to Full Player
- Animation: 
  - Scale up from mini player position
  - 400ms duration, emphasized deceleration curve
  - Content crossfade
- Preserve playback state

**Swipe Up:**
- Same as tap: Expand to Full Player
- Threshold: 50dp vertical displacement
- Velocity-sensitive: Fast swipe = instant expand

**Swipe Down:**
- Action: Hide mini player temporarily
- Threshold: 100dp vertical displacement
- Re-appears on next track change or if playback controls accessed
- Optional setting to disable this gesture

**Swipe Left/Right:**
- Action: Skip to next/previous track
- Threshold: 80dp horizontal displacement
- Visual feedback: Card translation follows finger
- Velocity-sensitive: Fast swipe = instant skip
- Animation: 
  - Swipe right = previous track, card slides right then back
  - Swipe left = next track, card slides left then back
  - 300ms duration

**Long Press:**
- Action: Show quick actions bottom sheet
- Vibrate: Medium intensity
- Sheet contents:
  - Add to playlist
  - Go to album (if available)
  - Go to artist
  - Share song
  - View queue

### 4.5.4 States

**Playing State:**
```
┌─────────────────────────────────────┐
│  ┌────┐  Blinding Lights             │
│  │ 🎵 │  The Weeknd     [❤️] [⏸️]  │
│  └────┘  ▓▓▓▓▓▓▓░░░░░  70%         │
└─────────────────────────────────────┘
```
- Play icon = Pause icon
- Progress bar animating

**Paused State:**
```
┌─────────────────────────────────────┐
│  ┌────┐  Blinding Lights             │
│  │ 🎵 │  The Weeknd     [❤️] [▶️]  │
│  └────┘  ▓▓▓▓▓▓▓░░░░░  70%         │
└─────────────────────────────────────┘
```
- Pause icon = Play icon
- Progress bar static

**Loading State:**
```
┌─────────────────────────────────────┐
│  ┌────┐  Blinding Lights             │
│  │ 🎵 │  The Weeknd     [❤️] [⏳]  │
│  └────┘  ░░░░░░░░░░░░  0%          │
└─────────────────────────────────────┘
```
- Play/pause replaced with spinner
- Progress bar empty

**Error State:**
```
┌─────────────────────────────────────┐
│  ┌────┐  Blinding Lights             │
│  │ ⚠️ │  Failed to load  [❤️] [↻]  │
│  └────┘                              │
└─────────────────────────────────────┘
```
- Error icon in thumbnail
- Retry button instead of play/pause

**No Song State:**
- Mini player hidden
- Only visible when there's an active or queued track

### 4.5.5 Visual Transitions

**Appear Animation (First Playback):**
- Slide up from bottom
- 300ms duration
- Overshoot interpolator for bounce effect

**Track Change Animation:**
- Thumbnail: Crossfade (200ms)
- Text: Slide + fade (250ms)
- Progress bar: Reset to 0 then animate

**Like Button Animation:**
- Scale pulse: 0.8 → 1.2 → 1.0
- Color transition: Outline → Filled red
- Duration: 300ms
- Spring animation

### 4.5.6 Responsiveness

**Different Screen Sizes:**
- Tablets: Mini player width limited to 600dp, centered
- Foldables: Adapt to screen configuration
- Landscape: Height reduced to 56dp

**Multi-Window:**
- Mini player visible in both app windows if app is in split-screen

**Gesture Navigation:**
- Bottom padding: 16dp on gesture nav devices
- Swipe up gesture: Doesn't conflict with system gesture

### 4.5.7 Performance

**Frame Rate:** 60 FPS during all animations
**Memory:** < 10 MB for mini player instance
**CPU:** < 1% during static display
**Battery:** Progress updates optimized (250ms intervals vs real-time)

### 4.5.8 Accessibility

**Content Description:**
- Thumbnail: "Album art for [Song Title]"
- Song info: "[Song Title] by [Artist]"
- Like button: "Like" or "Unlike"
- Play/pause: "Play" or "Pause"

**TalkBack:**
- Readable track info
- Actionable buttons
- Progress announcement on request

**Touch Target Sizes:**
- All interactive elements: Minimum 48dp × 48dp

---

## 4.6 Full Player

### 4.6.1 Overview
Immersive full-screen music player with dynamic Material You theming, gesture controls, and comprehensive playback management.

### 4.6.2 UI Layout

```
┌─────────────────────────────────────┐
│  [↓]                          [⋮]   │  Top Bar
│                                     │
│                                     │
│         ┌───────────────┐           │
│         │               │           │
│         │   Album Art   │           │  Album Art
│         │   (300dp)     │           │  (Dynamic color source)
│         │               │           │
│         └───────────────┘           │
│                                     │
│  ────────────────────────────────   │  Progress Bar
│  1:23              -1:42            │  Time stamps
│                                     │
│         Song Title                  │  Metadata
│         Artist Name                 │
│         Album Name • Year           │
│                                     │
│  [❤️]  [🔀]  [⏮️]  [⏸️]  [⏭️]  [🔁] │  Controls
│                                     │
│                                     │
│  ═══════════════════════════════   │  Queue Preview
│  Next in queue                      │
│  ┌────┐  Next Song Title            │
│  │ 🎵 │  Artist               [⋮]  │
│  └────┘                             │
│                                     │
└─────────────────────────────────────┘
```

### 4.6.3 Component Details

#### Top Bar

**Layout:**
```
┌─────────────────────────────────────┐
│  [↓ Chevron]              [⋮ Menu]  │
└─────────────────────────────────────┘
```

**Chevron Button:**
- Function: Collapse to mini player
- Size: 24dp icon, 48dp touch target
- Animation: Rotate 180° when expanding/collapsing

**Menu Button:**
- Function: Show options menu
- Options:
  - Add to playlist
  - Go to album
  - Go to artist
  - Share song
  - Sleep timer
  - Audio quality
  - Equalizer
  - Lyrics (if available)

**Background:**
- Translucent with blur effect
- Surface elevation: 0dp (seamless with content)

#### Album Art

**Dimensions:**
- Size: 300dp × 300dp (portrait)
- Size: 240dp × 240dp (landscape)
- Corner radius: 16dp
- Shadow: 8dp elevation

**Dynamic Theming:**
- Extract dominant colors from album art
- Apply to:
  - Background gradient
  - Button tint colors
  - Progress bar color
  - Status bar color
  - Navigation bar color
- Update animation: 500ms crossfade

**Color Extraction Algorithm:**
```kotlin
suspend fun extractColors(bitmap: Bitmap): DynamicColors {
    return withContext(Dispatchers.Default) {
        val palette = Palette.from(bitmap).generate()
        
        DynamicColors(
            primary = palette.vibrantSwatch?.rgb 
                ?: palette.dominantSwatch?.rgb 
                ?: Color.Blue,
            
            background = palette.darkMutedSwatch?.rgb 
                ?: Color.DarkGray,
            
            surface = palette.lightMutedSwatch?.rgb 
                ?: Color.Gray
        )
    }
}
```

**Loading State:**
- Show shimmer placeholder
- Fade in when loaded
- Fallback: Music note icon on colored background

**Gestures on Album Art:**
- **Swipe Left:** Next track
- **Swipe Right:** Previous track
- **Swipe Down:** Collapse to mini player
- **Double Tap:** Play/Pause
- **Pinch Zoom:** Fullscreen album art view (optional)

#### Progress Bar

**Layout:**
```
────────────────────●─────────────
1:23                       -1:42
```

**Components:**
- **Track:** Full width, 4dp height
- **Progress:** Primary color, rounded caps
- **Thumb:** 12dp circle (visible on touch)
- **Time Elapsed:** Left-aligned, 14sp
- **Time Remaining:** Right-aligned, 14sp (negative format)

**Interactions:**
- **Drag:** Seek to position
- **Tap:** Jump to position
- **Haptic:** Light feedback on drag start
- **Precision:** Second-level accuracy

**States:**
- **Playing:** Animates smoothly
- **Buffering:** Shows buffered range in secondary color
- **Seeking:** Thumb enlarges to 16dp, shows preview timestamp

#### Metadata Section

**Layout:**
```
        Song Title Here
        Artist Name
        Album Name • 2024
```

**Song Title:**
- Font: 24sp, Medium weight
- Max lines: 2
- Scrolling: Marquee if overflow
- Color: On-surface (high emphasis)

**Artist Name:**
- Font: 16sp, Regular weight
- Max lines: 1
- Clickable: Navigate to artist page
- Color: On-surface (medium emphasis)

**Album/Year:**
- Font: 14sp, Regular weight
- Format: "Album Name • Year"
- Clickable: Navigate to album (if available)
- Color: On-surface (medium emphasis)

**Spacing:**
- Title-to-artist: 4dp
- Artist-to-album: 2dp

#### Playback Controls

**Layout:**
```
[❤️]  [🔀]  [⏮️]  [⏸️]  [⏭️]  [🔁]
```

**Buttons (Left to Right):**

1. **Like/Unlike**
   - Icon: Heart (filled/outline)
   - Size: 24dp
   - Action: Toggle like status
   - Animation: Scale + color pulse

2. **Shuffle**
   - Icon: Shuffle icon
   - Size: 24dp
   - States: Off (gray), On (primary color)
   - Action: Toggle shuffle mode

3. **Previous Track**
   - Icon: Skip previous
   - Size: 32dp
   - Action: 
     - If < 3 seconds elapsed: Previous track
     - If > 3 seconds elapsed: Restart current track
   - Long press: Rewind (2x speed)

4. **Play/Pause (Primary)**
   - Icon: Play/Pause
   - Size: 64dp (largest)
   - Background: Primary color circle (80dp)
   - Elevation: 4dp
   - Animation: Morph between play/pause icons (300ms)
   - Action: Toggle playback

5. **Next Track**
   - Icon: Skip next
   - Size: 32dp
   - Action: Play next track
   - Long press: Fast forward (2x speed)

6. **Repeat**
   - Icon: Repeat icon
   - Size: 24dp
   - States: 
     - Off (gray)
     - Repeat All (primary color)
     - Repeat One (primary color + "1" badge)
   - Action: Cycle through modes

**Touch Targets:**
- Minimum: 48dp × 48dp for all buttons
- Spacing: 24dp between buttons

**Loading State:**
- Primary button shows spinner
- Other buttons disabled (50% opacity)

#### Queue Preview

**Layout:**
```
═══════════════════════════════
Next in queue                 [↑]

┌────┐  Next Song Title
│ 🎵 │  Artist Name          [⋮]
└────┘

┌────┐  Song After That
│ 🎵 │  Artist Name          [⋮]
└────┘
```

**Features:**
- Show next 2-3 songs
- Expandable: Tap header or swipe up to show full queue
- Drag handle: Reorder queue items
- Swipe left: Remove from queue

**Full Queue View:**
```
┌─────────────────────────────────────┐
│  [↓] Queue (12 songs)  [Clear All]  │
├─────────────────────────────────────┤
│  Now Playing                        │
│  ┌────┐  Current Song          3:42│
│  │ ▶  │  Artist Name                │
│  └────┘                             │
│                                     │
│  Next in Queue                      │
│  ┌────┐  Song 1               3:15 │
│  │ ══ │  Artist               [⋮]  │
│  └────┘                             │
│                                     │
│  ┌────┐  Song 2               4:20 │
│  │ ══ │  Artist               [⋮]  │
│  └────┘                             │
│                                     │
│  [Load more...]                     │
└─────────────────────────────────────┘
```

### 4.6.4 Gesture Controls

**Swipe Down:**
- **Threshold:** 100dp vertical displacement
- **Action:** Collapse to mini player
- **Animation:** 
  - Reverse of expand animation
  - 400ms duration
  - Follow finger on drag

**Swipe Left:**
- **Threshold:** 80dp horizontal displacement
- **Action:** Next track
- **Visual:** 
  - Album art slides left
  - Next album art slides in from right
  - 300ms animation

**Swipe Right:**
- **Threshold:** 80dp horizontal displacement
- **Action:** Previous track (or restart if > 3s)
- **Visual:** 
  - Album art slides right
  - Previous album art slides in from left
  - 300ms animation

**Swipe Up (from queue preview):**
- **Action:** Expand to full queue view
- **Threshold:** 50dp

**Double Tap (on album art):**
- **Action:** Play/Pause toggle
- **Haptic:** Medium feedback

### 4.6.5 Dynamic Theming

**Color Extraction:**
- Source: Album art dominant colors
- Update: On each track change
- Transition: 500ms smooth gradient

**Applied Elements:**
- Background: Dark gradient from extracted color
- Controls: Tint with extracted accent color
- Progress bar: Primary extracted color
- System UI: Status bar + nav bar match theme

**Example:**
```kotlin
@Composable
fun FullPlayerScreen(song: Song) {
    val colors = remember(song.albumArtUrl) {
        extractDynamicColors(song.albumArtUrl)
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        colors.background,
                        colors.background.copy(alpha = 0.8f)
                    )
                )
            )
    ) {
        // Player content
    }
}
```

### 4.6.6 Additional Features

**Lyrics (Optional Feature):**
- Bottom sheet with scrolling lyrics
- Synced highlighting if timestamps available
- Auto-scroll with playback

**Sleep Timer:**
- Options: 5, 10, 15, 30, 45, 60 mins, End of track
- Shows countdown in menu
- Fade out audio before stopping

**Audio Quality Indicator:**
- Badge showing current stream quality
- Tap to temporarily change quality

**Visualizer (Optional):**
- Audio waveform/spectrum visualization
- Rendered above/behind album art
- Toggle in settings

### 4.6.7 Performance

- **Animation Frame Rate:** 60 FPS
- **Color Extraction:** < 100ms
- **Gesture Response:** < 16ms
- **Theme Transition:** Smooth, no jank

### 4.6.8 Accessibility

**Screen Reader:**
- Announce track changes
- Readable playback time
- Action descriptions for all buttons

**Font Scaling:**
- Support up to 200% text scaling
- Layout adjusts dynamically

**Contrast:**
- Ensure WCAG AA compliance
- High contrast mode support

---

## 5. Backend Architecture

### 5.1 Architecture Overview

**Pattern:** MVVM + Clean Architecture

```
┌─────────────────────────────────────────────┐
│           Presentation Layer                │
│  ┌─────────────────────────────────────┐   │
│  │  Jetpack Compose UI                 │   │
│  │  - Screens (Home, Search, etc.)     │   │
│  │  - Components (Player, Cards)       │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │  ViewModels                         │   │
│  │  - HomeViewModel                    │   │
│  │  - SearchViewModel                  │   │
│  │  - PlayerViewModel                  │   │
│  │  - LibraryViewModel                 │   │
│  └──────────────┬──────────────────────┘   │
└─────────────────┼───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│            Domain Layer                     │
│  ┌─────────────────────────────────────┐   │
│  │  Use Cases                          │   │
│  │  - GetHomeContentUseCase            │   │
│  │  - SearchSongsUseCase               │   │
│  │  - PlaySongUseCase                  │   │
│  │  - ManageQueueUseCase               │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │  Domain Models                      │   │
│  │  - Song, Artist, Playlist           │   │
│  └─────────────────────────────────────┘   │
└─────────────────┼───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│             Data Layer                      │
│  ┌─────────────────────────────────────┐   │
│  │  Repositories                       │   │
│  │  - SongRepository                   │   │
│  │  - PlaylistRepository               │   │
│  │  - CacheRepository                  │   │
│  └──────┬──────────────┬────────────────┘   │
│         │              │                    │
│  ┌──────▼──────┐  ┌───▼────────────────┐   │
│  │  Remote     │  │  Local             │   │
│  │  Data       │  │  Data Sources      │   │
│  │  Sources    │  │                    │   │
│  │  - YouTubei │  │  - Room DB         │   │
│  │  - NewPipe  │  │  - DataStore       │   │
│  └─────────────┘  │  - File System     │   │
│                   └────────────────────┘   │
└─────────────────────────────────────────────┘
```

### 5.2 Data Layer

#### 5.2.1 Room Database Schema

**Database Name:** `sonic_music.db`

**Entities:**

```kotlin
@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val duration: Int, // seconds
    val thumbnailUrl: String,
    val year: Int?,
    val category: String,
    val viewCount: Long?,
    val isLiked: Boolean = false,
    val likedAt: Long? = null,
    val cachedStreamUrl: String? = null,
    val cacheExpiry: Long? = null
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String?,
    val coverArtUrl: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "playlist_songs",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId"), Index("songId")]
)
data class PlaylistSongCrossRef(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: String,
    val position: Int,
    val addedAt: Long
)

@Entity(tableName = "playback_history")
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val playedAt: Long,
    val playDuration: Int, // How long played in seconds
    val completed: Boolean
)

@Entity(tableName = "local_songs")
data class LocalSongEntity(
    @PrimaryKey val id: Long, // MediaStore ID
    val title: String,
    val artist: String,
    val album: String?,
    val duration: Int,
    val filePath: String,
    val albumId: Long?,
    val dateAdded: Long,
    val fileSize: Long
)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long
)

@Entity(tableName = "cached_streams")
data class CachedStreamEntity(
    @PrimaryKey val songId: String,
    val streamUrl: String,
    val quality: String,
    val cachedAt: Long,
    val expiresAt: Long,
    val fileSize: Long
)
```

**DAOs:**

```kotlin
@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE isLiked = 1 ORDER BY likedAt DESC")
    fun getLikedSongs(): Flow<List<SongEntity>>
    
    @Query("SELECT * FROM songs WHERE id = :songId")
    suspend fun getSongById(songId: String): SongEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity)
    
    @Update
    suspend fun updateSong(song: SongEntity)
    
    @Query("UPDATE songs SET isLiked = :liked, likedAt = :timestamp WHERE id = :songId")
    suspend fun updateLikeStatus(songId: String, liked: Boolean, timestamp: Long?)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>
    
    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistWithSongs(playlistId: Long): PlaylistWithSongs?
    
    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long
    
    @Insert
    suspend fun addSongToPlaylist(crossRef: PlaylistSongCrossRef)
    
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String)
    
    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)
}

@Dao
interface PlaybackHistoryDao {
    @Query("SELECT DISTINCT songId, playedAt FROM playback_history ORDER BY playedAt DESC LIMIT 100")
    fun getRecentlyPlayed(): Flow<List<PlaybackHistoryEntity>>
    
    @Insert
    suspend fun insertPlayback(history: PlaybackHistoryEntity)
    
    @Query("DELETE FROM playback_history WHERE id NOT IN (SELECT id FROM playback_history ORDER BY playedAt DESC LIMIT 100)")
    suspend fun pruneOldHistory()
    
    @Query("DELETE FROM playback_history")
    suspend fun clearAllHistory()
}

@Dao
interface LocalSongDao {
    @Query("SELECT * FROM local_songs ORDER BY title ASC")
    fun getAllLocalSongs(): Flow<List<LocalSongEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<LocalSongEntity>)
    
    @Query("DELETE FROM local_songs")
    suspend fun deleteAll()
}

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY searchedAt DESC LIMIT 20")
    fun getRecentSearches(): Flow<List<RecentSearchEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(search: RecentSearchEntity)
    
    @Query("DELETE FROM recent_searches WHERE query = :query")
    suspend fun deleteSearch(query: String)
    
    @Query("DELETE FROM recent_searches")
    suspend fun clearAll()
}
```

#### 5.2.2 Repositories

**SongRepository:**

```kotlin
interface SongRepository {
    suspend fun searchSongs(query: String, limit: Int = 20): Result<List<Song>>
    suspend fun getSongById(id: String): Result<Song>
    suspend fun getStreamUrl(songId: String, quality: StreamQuality): Result<String>
    suspend fun getSongDetails(songId: String): Result<SongDetails>
    suspend fun likeSong(songId: String)
    suspend fun unlikeSong(songId: String)
    fun getLikedSongs(): Flow<List<Song>>
}

class SongRepositoryImpl @Inject constructor(
    private val youtubeService: YouTubeiService,
    private val newPipeExtractor: NewPipeExtractor,
    private val songDao: SongDao,
    private val cacheManager: CacheManager
) : SongRepository {
    
    override suspend fun searchSongs(query: String, limit: Int): Result<List<Song>> {
        return try {
            val results = youtubeService.search(query, limit)
            val filteredSongs = results
                .filter { isValidSong(it) }
                .map { it.toSong() }
            
            // Cache to database
            songDao.insertAll(filteredSongs.map { it.toEntity() })
            
            Result.success(filteredSongs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getStreamUrl(songId: String, quality: StreamQuality): Result<String> {
        // Check cache first
        val cached = cacheManager.getCachedStreamUrl(songId, quality)
        if (cached != null && !cached.isExpired()) {
            return Result.success(cached.url)
        }
        
        return try {
            val streamUrl = newPipeExtractor.extractAudioStream(songId, quality.bitrate)
            
            // Cache URL with 6-hour expiry
            cacheManager.cacheStreamUrl(songId, streamUrl, quality, expiryHours = 6)
            
            Result.success(streamUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun isValidSong(item: SearchResult): Boolean {
        val excludePatterns = listOf(
            "dj mix", "mashup", "album", "best of",
            "compilation", "live set", "full album"
        )
        
        val titleLower = item.title.lowercase()
        if (excludePatterns.any { titleLower.contains(it) }) return false
        if (item.duration < 90 || item.duration > 480) return false
        if (item.category != "Music") return false
        
        return true
    }
}
```

**PlaylistRepository:**

```kotlin
interface PlaylistRepository {
    fun getAllPlaylists(): Flow<List<Playlist>>
    suspend fun getPlaylistWithSongs(playlistId: Long): Result<PlaylistWithSongs>
    suspend fun createPlaylist(name: String, description: String?): Result<Long>
    suspend fun addSongToPlaylist(playlistId: Long, songId: String): Result<Unit>
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String): Result<Unit>
    suspend fun deletePlaylist(playlistId: Long): Result<Unit>
    suspend fun updatePlaylistOrder(playlistId: Long, songIds: List<String>): Result<Unit>
}

class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao
) : PlaylistRepository {
    
    override fun getAllPlaylists(): Flow<List<Playlist>> {
        return playlistDao.getAllPlaylists()
            .map { entities -> entities.map { it.toPlaylist() } }
    }
    
    override suspend fun createPlaylist(name: String, description: String?): Result<Long> {
        return try {
            val playlist = PlaylistEntity(
                name = name,
                description = description,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val id = playlistDao.insertPlaylist(playlist)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun addSongToPlaylist(playlistId: Long, songId: String): Result<Unit> {
        return try {
            val existingSongs = playlistDao.getPlaylistSongs(playlistId)
            val position = existingSongs.size
            
            val crossRef = PlaylistSongCrossRef(
                playlistId = playlistId,
                songId = songId,
                position = position,
                addedAt = System.currentTimeMillis()
            )
            
            playlistDao.addSongToPlaylist(crossRef)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

**CacheRepository:**

```kotlin
interface CacheRepository {
    suspend fun getCachedStreamUrl(songId: String, quality: StreamQuality): CachedStream?
    suspend fun cacheStreamUrl(songId: String, url: String, quality: StreamQuality, expiryHours: Int)
    suspend fun downloadSong(songId: String, quality: StreamQuality): Result<String>
    suspend fun getDownloadedSongs(): Flow<List<DownloadedSong>>
    suspend fun deleteDownload(songId: String): Result<Unit>
    suspend fun clearCache(): Result<Long> // Returns bytes cleared
}

class CacheRepositoryImpl @Inject constructor(
    private val cacheDao: CachedStreamDao,
    private val fileManager: FileManager,
    private val downloadManager: DownloadManager
) : CacheRepository {
    
    override suspend fun cacheStreamUrl(
        songId: String, 
        url: String, 
        quality: StreamQuality, 
        expiryHours: Int
    ) {
        val entity = CachedStreamEntity(
            songId = songId,
            streamUrl = url,
            quality = quality.name,
            cachedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + (expiryHours * 3600 * 1000),
            fileSize = 0L
        )
        cacheDao.insert(entity)
    }
    
    override suspend fun downloadSong(songId: String, quality: StreamQuality): Result<String> {
        return try {
            val streamUrl = getStreamUrl(songId, quality)
            val filePath = fileManager.getDownloadPath(songId)
            
            downloadManager.downloadFile(streamUrl, filePath)
            
            Result.success(filePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun clearCache(): Result<Long> {
        return try {
            val bytesCleared = fileManager.clearCacheDirectory()
            cacheDao.deleteExpired(System.currentTimeMillis())
            Result.success(bytesCleared)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

### 5.3 Domain Layer

#### Use Cases

```kotlin
class GetHomeContentUseCase @Inject constructor(
    private val songRepository: SongRepository,
    private val historyRepository: HistoryRepository,
    private val recommendationService: RecommendationService
) {
    suspend operator fun invoke(): Result<HomeContent> {
        return try {
            val listenAgain = historyRepository.getRecentlyPlayed(limit = 15)
            val quickPicks = recommendationService.getPersonalizedSongs(limit = 20)
            val forgottenFavorites = recommendationService.getForgottenFavorites(limit = 15)
            val newReleases = songRepository.getNewReleases(limit = 25)
            val trending = songRepository.getTrending(limit = 30)
            val englishHits = songRepository.getEnglishHits(limit = 25)
            val artists = recommendationService.getTopArtistSongs(limit = 8)
            
            Result.success(
                HomeContent(
                    listenAgain = listenAgain,
                    quickPicks = quickPicks,
                    forgottenFavorites = forgottenFavorites,
                    newReleases = newReleases,
                    trending = trending,
                    englishHits = englishHits,
                    artists = artists
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class SearchSongsUseCase @Inject constructor(
    private val songRepository: SongRepository,
    private val recentSearchRepository: RecentSearchRepository
) {
    suspend operator fun invoke(query: String): Result<List<Song>> {
        if (query.isBlank()) return Result.success(emptyList())
        
        // Save to recent searches
        recentSearchRepository.addSearch(query)
        
        return songRepository.searchSongs(query)
    }
}

class PlaySongUseCase @Inject constructor(
    private val playerService: PlayerService,
    private val historyRepository: HistoryRepository,
    private val cacheRepository: CacheRepository
) {
    suspend operator fun invoke(song: Song, replaceQueue: Boolean = false): Result<Unit> {
        return try {
            // Get stream URL
            val streamUrl = cacheRepository.getCachedStreamUrl(song.id, currentQuality)
                ?: songRepository.getStreamUrl(song.id, currentQuality).getOrThrow()
            
            // Play song
            if (replaceQueue) {
                playerService.playNow(song, streamUrl)
            } else {
                playerService.addToQueue(song, streamUrl)
            }
            
            // Record in history
            historyRepository.recordPlayback(song.id)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class ManageQueueUseCase @Inject constructor(
    private val playerService: PlayerService
) {
    fun getQueue(): Flow<List<Song>> = playerService.queue
    
    suspend fun addToQueue(song: Song) {
        playerService.addToQueue(song)
    }
    
    suspend fun playNext(song: Song) {
        playerService.addToQueueNext(song)
    }
    
    suspend fun removeFromQueue(position: Int) {
        playerService.removeFromQueue(position)
    }
    
    suspend fun reorderQueue(from: Int, to: Int) {
        playerService.moveQueueItem(from, to)
    }
    
    suspend fun clearQueue() {
        playerService.clearQueue()
    }
}
```

### 5.4 Playback Service

#### PlayerService (Media3 MediaSessionService)

```kotlin
class PlayerService : MediaSessionService() {
    
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val queue = MutableStateFlow<List<Song>>(emptyList())
    
    override fun onCreate() {
        super.onCreate()
        
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(WAKE_MODE_LOCAL)
            .build()
        
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(MediaSessionCallback())
            .build()
        
        setupNotification()
    }
    
    private fun setupNotification() {
        val notificationManager = MediaNotificationManager(
            context = this,
            sessionToken = mediaSession.token
        )
        notificationManager.showNotificationForPlayer(player)
    }
    
    suspend fun playNow(song: Song, streamUrl: String) {
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(Uri.parse(song.thumbnailUrl))
                    .build()
            )
            .build()
        
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }
    
    inner class MediaSessionCallback : MediaSession.Callback {
        override fun onPlay(session: MediaSession, controller: MediaSession.ControllerInfo) {
            player.play()
        }
        
        override fun onPause(session: MediaSession, controller: MediaSession.ControllerInfo) {
            player.pause()
        }
        
        override fun onSeekToNext(session: MediaSession, controller: MediaSession.ControllerInfo) {
            player.seekToNext()
        }
        
        override fun onSeekToPrevious(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (player.currentPosition > 3000) {
                player.seekTo(0)
            } else {
                player.seekToPrevious()
            }
        }
    }
    
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession
    }
    
    override fun onDestroy() {
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
```

### 5.5 Dependency Injection (Hilt)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SonicMusicDatabase {
        return Room.databaseBuilder(
            context,
            SonicMusicDatabase::class.java,
            "sonic_music.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }
    
    @Provides
    fun provideSongDao(database: SonicMusicDatabase) = database.songDao()
    
    @Provides
    fun providePlaylistDao(database: SonicMusicDatabase) = database.playlistDao()
    
    @Provides
    @Singleton
    fun provideYouTubeiService(): YouTubeiService {
        return YouTubeiServiceImpl()
    }
    
    @Provides
    @Singleton
    fun provideNewPipeExtractor(): NewPipeExtractor {
        return NewPipeExtractor()
    }
    
    @Provides
    @Singleton
    fun provideSongRepository(
        youtubeService: YouTubeiService,
        newPipeExtractor: NewPipeExtractor,
        songDao: SongDao,
        cacheManager: CacheManager
    ): SongRepository {
        return SongRepositoryImpl(youtubeService, newPipeExtractor, songDao, cacheManager)
    }
}

@Module
@InstallIn(ViewModelComponent::class)
object ViewModelModule {
    
    @Provides
    fun provideGetHomeContentUseCase(
        songRepository: SongRepository,
        historyRepository: HistoryRepository,
        recommendationService: RecommendationService
    ): GetHomeContentUseCase {
        return GetHomeContentUseCase(songRepository, historyRepository, recommendationService)
    }
}
```

---

## 6. Edge Cases & Error Handling

### 6.1 Network Errors

**Scenarios:**
- No internet connection
- API rate limiting
- Server timeouts
- Slow network

**Handling:**

```kotlin
sealed class NetworkError {
    object NoConnection : NetworkError()
    object RateLimited : NetworkError()
    object Timeout : NetworkError()
    object ServerError : NetworkError()
}

class NetworkErrorHandler {
    fun handle(error: Throwable): NetworkError {
        return when (error) {
            is UnknownHostException, is ConnectException -> NetworkError.NoConnection
            is SocketTimeoutException -> NetworkError.Timeout
            is HttpException -> {
                when (error.code()) {
                    429 -> NetworkError.RateLimited
                    else -> NetworkError.ServerError
                }
            }
            else -> NetworkError.ServerError
        }
    }
}
```

**UI Response:**

- **No Connection:**
  - Show friendly error message
  - Offer offline mode (play local/cached songs)
  - Retry button
  
- **Rate Limited:**
  - Implement exponential backoff
  - Show "Too many requests, please wait"
  - Auto-retry after delay

- **Timeout:**
  - Show loading indicator with timeout warning
  - Offer retry or cancel options

### 6.2 Playback Errors

**Scenarios:**
- Stream URL expired
- Unsupported format
- Corrupted audio
- Insufficient storage for cache

**Handling:**

```kotlin
player.addListener(object : Player.Listener {
    override fun onPlayerError(error: PlaybackException) {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> {
                // Try to refresh stream URL
                refreshStreamAndRetry()
            }
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                // Skip to next track
                player.seekToNext()
                showError("Unsupported format")
            }
            else -> {
                showError("Playback error occurred")
            }
        }
    }
})
```

**UI Response:**
- Display error in mini/full player
- Offer skip to next track
- Automatic retry for transient errors (max 3 attempts)

### 6.3 Search Edge Cases

**Empty Query:**
- Show recent searches
- Don't trigger API call

**No Results:**
- Display "No songs found"
- Suggest alternative queries
- Check spelling

**Special Characters:**
- Sanitize input before search
- Handle Unicode properly

**Very Long Queries (>200 chars):**
- Truncate and warn user

### 6.4 Data Integrity

**Duplicate Detection:**

```kotlin
fun removeDuplicates(songs: List<Song>): List<Song> {
    return songs.distinctBy { 
        "${it.title.lowercase().trim()}-${it.artist.lowercase().trim()}"
    }
}
```

**Orphaned Data:**
- Background job to clean orphaned playlist songs
- Remove expired cache entries

**Database Corruption:**
- Implement database backup/restore
- Fallback to destructive migration if critical

### 6.5 Permission Handling

**Storage Permission (Local Songs):**

```kotlin
@Composable
fun LocalSongsScreen() {
    val permissionState = rememberPermissionState(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    )
    
    LaunchedEffect(Unit) {
        if (!permissionState.hasPermission) {
            permissionState.launchPermissionRequest()
        }
    }
    
    when {
        permissionState.hasPermission -> {
            LocalSongsList()
        }
        permissionState.shouldShowRationale -> {
            PermissionRationale {
                permissionState.launchPermissionRequest()
            }
        }
        else -> {
            PermissionDenied()
        }
    }
}
```

**Notification Permission (Android 13+):**
- Request on first playback
- Explain importance for playback controls
- Gracefully degrade if denied (no notification controls)

### 6.6 Memory Management

**Large Playlists:**
- Paginate playlist display (load 50 at a time)
- Virtual scrolling with LazyColumn

**Image Loading:**
- Memory cache limit: 50 MB
- Disk cache limit: 200 MB
- Auto-clear old cached images

**Queue Size:**
- Limit queue to 500 songs
- Warn user if adding more
### 6.7 Battery Optimization

**Background Playback:**
- Respect system battery saver mode
- Reduce notification update frequency on low battery
- Pause playback if battery < 5% (with user preference option)

**Location:**
- Don't request location permission (not needed)

### 6.8 Concurrent Playback

**Multiple Instances:**
- Prevent multiple app instances playing simultaneously
- MediaSession handles audio focus properly

**Phone Calls:**
- Pause on incoming call
- Resume after call ends (if user preference enabled)

**Other Media:**
- Request audio focus (AUDIOFOCUS_GAIN)
- Duck volume for notifications
- Pause when another app requests exclusive audio focus

---

## 7. Performance Optimization

### 7.1 Startup Performance

**Target:** Cold start < 2 seconds

**Optimizations:**
- Lazy initialization of non-critical components
- Defer database queries until needed
- Use WorkManager for background tasks
- Profile with Android Studio Profiler

```kotlin
class SonicMusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Critical initialization
        Hilt.inject(this)
        
        // Deferred initialization
        lifecycleScope.launch {
            delay(2000)
            initializeNonCriticalComponents()
        }
    }
    
    private suspend fun initializeNonCriticalComponents() {
        withContext(Dispatchers.IO) {
            // Initialize cache cleaner
            CacheCleaner.scheduleCleanup()
            
            // Pre-load settings
            SettingsManager.preloadSettings()
        }
    }
}
```

### 7.2 UI Performance

**Target:** 60 FPS (16ms per frame)

**Compose Optimizations:**

```kotlin
@Composable
fun SongItem(song: Song) {
    // Use remember to avoid recomposition
    val formattedDuration = remember(song.duration) {
        formatDuration(song.duration)
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = { /* Handle click */ },
                // Reduce ripple size for better performance
                indication = rememberRipple(bounded = true)
            )
    ) {
        // Use keys for LazyColumn items
        AsyncImage(
            model = song.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(56.dp)
        )
        
        Column {
            Text(song.title)
            Text(song.artist)
        }
    }
}
```

**List Performance:**
- Use `LazyColumn` with `key` parameter
- Implement item recycling
- Avoid nested scrollables
- Paginate large lists

### 7.3 Network Performance

**Caching Strategy:**

```kotlin
class CachingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        
        // Cache GET requests for 6 hours
        if (request.method == "GET") {
            val cacheControl = CacheControl.Builder()
                .maxAge(6, TimeUnit.HOURS)
                .build()
            
            return response.newBuilder()
                .header("Cache-Control", cacheControl.toString())
                .build()
        }
        
        return response
    }
}
```

**Parallel Requests:**
- Load home sections in parallel using `async`
- Combine results with `awaitAll()`

**Request Coalescing:**
- Batch similar requests
- Debounce search queries (300ms)

### 7.4 Database Performance

**Indexing:**

```kotlin
@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["isLiked"]),
        Index(value = ["artist"])
    ]
)
data class SongEntity(...)
```

**Query Optimization:**
- Use `Flow` for reactive queries
- Limit result sets
- Use projections for partial data

**Transactions:**

```kotlin
@Transaction
suspend fun updatePlaylistWithSongs(playlistId: Long, songIds: List<String>) {
    // All operations in single transaction
    playlistDao.clearPlaylistSongs(playlistId)
    songIds.forEachIndexed { index, songId ->
        playlistDao.addSongToPlaylist(
            PlaylistSongCrossRef(playlistId, songId, index)
        )
    }
}
```

### 7.5 Media Playback Performance

**Buffer Configuration:**

```kotlin
val loadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        15000, // Min buffer: 15s
        50000, // Max buffer: 50s
        1500,  // Playback buffer: 1.5s
        2500   // Playback rebuffer: 2.5s
    )
    .build()

player = ExoPlayer.Builder(context)
    .setLoadControl(loadControl)
    .build()
```

**Adaptive Bitrate:**
- Start with lower quality
- Upgrade as buffer increases
- Downgrade on network issues

### 7.6 Memory Optimization

**Image Loading:**

```kotlin
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(song.thumbnailUrl)
        .crossfade(true)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .size(56.dp.value.toInt()) // Request specific size
        .build(),
    contentDescription = null
)
```

**Bitmap Scaling:**
- Load scaled bitmaps for thumbnails
- Use ARGB_8888 only when necessary
- Prefer RGB_565 for non-transparent images

**Leak Prevention:**
- Use `viewModelScope` for coroutines
- Cancel jobs on ViewModel clear
- Avoid context leaks in listeners

---

## 8. Testing Strategy

### 8.1 Unit Tests

**Coverage Target:** > 80%

**Test Examples:**

```kotlin
class SongRepositoryTest {
    
    @Test
    fun `searchSongs filters out DJ mixes`() = runTest {
        val repository = FakeSongRepository()
        val results = repository.searchSongs("test query")
        
        assertThat(results.getOrNull()).isNotNull()
        assertThat(results.getOrNull()!!).noneMatch { 
            it.title.contains("DJ Mix", ignoreCase = true)
        }
    }
    
    @Test
    fun `getStreamUrl returns cached URL if not expired`() = runTest {
        val repository = SongRepositoryImpl(
            youtubeService = FakeYouTubeService(),
            newPipeExtractor = mock(),
            cacheDao = FakeCacheDao(),
            cacheManager = FakeCacheManager()
        )
        
        // Setup: cache a URL
        repository.cacheStreamUrl("song123", "https://stream.url", expiryHours = 1)
        
        // Test: should return cached URL without calling extractor
        val result = repository.getStreamUrl("song123", StreamQuality.HIGH)
        
        assertThat(result.isSuccess).isTrue()
        verify(exactly = 0) { newPipeExtractor.extractAudioStream(any(), any()) }
    }
}

class PlaySongUseCaseTest {
    
    @Test
    fun `playSong records playback in history`() = runTest {
        val historyRepo = FakeHistoryRepository()
        val useCase = PlaySongUseCase(
            playerService = FakePlayerService(),
            historyRepository = historyRepo,
            cacheRepository = FakeCacheRepository()
        )
        
        val song = Song(id = "123", title = "Test Song", artist = "Artist")
        useCase(song)
        
        assertThat(historyRepo.getHistory()).contains(song.id)
    }
}
```

### 8.2 UI Tests

**Test Examples:**

```kotlin
@HiltAndroidTest
class HomeScreenTest {
    
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)
    
    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()
    
    @Test
    fun homeScreen_displaysListenAgainSection() {
        composeRule.onNodeWithText("Listen Again").assertIsDisplayed()
        composeRule.onNodeWithTag("listen_again_list").assertIsDisplayed()
    }
    
    @Test
    fun songCard_click_startPlayback() {
        composeRule.onNodeWithTag("song_card_0").performClick()
        composeRule.onNodeWithTag("mini_player").assertIsDisplayed()
    }
}

class PlayerGestureTest {
    
    @get:Rule
    val composeRule = createComposeRule()
    
    @Test
    fun fullPlayer_swipeDown_collapsesToMiniPlayer() {
        composeRule.setContent {
            FullPlayerScreen(song = testSong)
        }
        
        composeRule.onNodeWithTag("full_player")
            .performTouchInput {
                swipeDown()
            }
        
        composeRule.onNodeWithTag("mini_player").assertIsDisplayed()
    }
}
```

### 8.3 Integration Tests

**Test Examples:**

```kotlin
@HiltAndroidTest
class PlaybackIntegrationTest {
    
    @Test
    fun playback_networkError_retriesWithExponentialBackoff() = runTest {
        // Setup: Inject fake repository that fails twice then succeeds
        val fakeRepo = FakeFailingRepository(failCount = 2)
        
        val viewModel = PlayerViewModel(
            playSongUseCase = PlaySongUseCase(fakeRepo, ...)
        )
        
        viewModel.playSong(testSong)
        
        advanceTimeBy(1000) // First retry after 1s
        advanceTimeBy(2000) // Second retry after 2s
        advanceTimeBy(4000) // Third attempt succeeds
        
        assertThat(viewModel.playerState.value).isInstanceOf(Playing::class)
    }
}
```

### 8.4 Performance Tests

```kotlin
@Test
fun homeScreen_loads_within2Seconds() {
    val startTime = System.currentTimeMillis()
    
    composeRule.setContent {
        HomeScreen()
    }
    
    composeRule.waitUntil(timeoutMillis = 2000) {
        composeRule.onAllNodesWithTag("song_card").fetchSemanticsNodes().isNotEmpty()
    }
    
    val loadTime = System.currentTimeMillis() - startTime
    assertThat(loadTime).isLessThan(2000)
}
```

---

## 9. Release Checklist

### 9.1 Pre-Release

- [ ] All unit tests passing (>80% coverage)
- [ ] All UI tests passing
- [ ] No memory leaks (LeakCanary clean)
- [ ] Performance profiling complete (no jank)
- [ ] Accessibility audit passed (TalkBack compatible)
- [ ] Security audit (ProGuard rules, no API keys in code)
- [ ] Privacy policy updated
- [ ] Terms of service updated
- [ ] App icons in all densities (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi)
- [ ] Screenshots for Play Store (all required sizes)
- [ ] Feature graphic created
- [ ] Video preview created (optional)

### 9.2 Build Configuration

**build.gradle (app level):**

```kotlin
android {
    defaultConfig {
        applicationId "com.sonicmusic.app"
        minSdk 26 // Android 8.0
        targetSdk 34 // Android 14
        versionCode 1
        versionName "1.0.0"
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    
    bundle {
        language {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
    }
}
```

**ProGuard Rules:**

```proguard
# Keep Media3 classes
-keep class androidx.media3.** { *; }

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Keep data classes
-keep class com.sonicmusic.app.domain.model.** { *; }
```

### 9.3 Permissions Review

**Required Permissions:**
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- For local songs -->
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```

### 9.4 Store Listing

**Title:** SonicMusic - Music Streaming

**Short Description:**
Modern music streaming app with Material You design. Discover, play, and enjoy your favorite songs.

**Full Description:**
SonicMusic brings you a beautiful, fast music streaming experience designed for Android.

KEY FEATURES:
• Material You dynamic theming
• Background playback with notification controls
• Offline playback with downloaded songs
• Create and manage playlists
• Smart recommendations and discovery
• Gesture-based player controls
• Local music file support
• No ads, no subscriptions

DESIGNED FOR ANDROID:
Built from the ground up for Android with Jetpack Compose and Material 3, SonicMusic adapts to your device's color scheme and provides a seamless, native experience.

DISCOVER MUSIC:
• Personalized recommendations
• Trending songs
• New releases
• Genre-based browsing
• Artist discovery

YOUR LIBRARY:
• Liked songs
• Custom playlists
• Playback history
• Local songs from device

POWERFUL PLAYER:
• Background playback
• Notification controls
• Equalizer with presets
• Sleep timer
• Crossfade and gapless playback
• Gesture controls (swipe to skip)

Privacy-focused with no unnecessary permissions. Your data stays on your device.

**Categories:**
- Primary: Music & Audio
- Secondary: Entertainment

**Content Rating:** Everyone

---

## 10. Future Enhancements (Post v1.0)

### 10.1 Phase 2 Features

1. **Lyrics Integration**
   - Fetch synced lyrics from multiple sources
   - Real-time highlighting during playback
   - Karaoke mode

2. **Social Features**
   - Share playlists with friends
   - Collaborative playlists
   - Listen along (real-time sync)

3. **Advanced Recommendations**
   - ML-based personalization
   - Mood-based playlists
   - "Discover Weekly" style recommendations

4. **Podcast Support**
   - Search and stream podcasts
   - Episode management
   - Playback speed control

### 10.2 Phase 3 Features

1. **Cloud Sync**
   - Sync playlists across devices
   - Cloud backup of preferences
   - Cross-platform support (iOS, Web)

2. **Advanced Audio**
   - Spatial audio support
   - High-resolution audio streaming
   - Custom DSP effects

3. **Smart Features**
   - Voice commands (Google Assistant)
   - Car mode UI
   - Wear OS companion app

### 10.3 Technical Debt & Optimizations

- Implement dependency caching for faster builds
- Add more comprehensive error tracking (Sentry/Firebase Crashlytics)
- Implement A/B testing framework
- Add analytics (privacy-preserving)
- Migrate to Kotlin Multiplatform for code sharing

---

## 11. Analytics & Instrumentation

### 11.1 Event Taxonomy (v1.0)

| Event | Description | Key Properties |
|------|-------------|----------------|
| `app_open` | App launched to foreground | `source`, `cold_start` |
| `home_section_loaded` | Home section content loaded | `section_name`, `latency_ms` |
| `search_submitted` | User submits a search | `query_length`, `source` |
| `search_result_play` | User plays from search results | `position`, `query_length` |
| `playback_start` | Playback begins | `song_id`, `source`, `network_type` |
| `playback_complete` | Track finishes | `song_id`, `duration_sec` |
| `playback_error` | Playback fails | `error_code`, `network_type` |
| `like_toggled` | Like/unlike action | `song_id`, `is_liked` |
| `playlist_created` | New playlist created | `name_length` |
| `download_completed` | Offline download finished | `song_id`, `file_size_mb` |

### 11.2 KPI Tracking
- D1/D7/D30 retention
- Average session duration and sessions per user
- Search-to-play conversion rate
- Playback success rate and error rate
- Offline usage rate (plays from downloads)
- Crash-free sessions
- Cache hit rate for stream URLs

### 11.3 Privacy Controls
- Analytics opt-out toggle in Settings
- No collection of PII (no email, phone, or account identifiers)
- Event batching to reduce network usage

---

## 12. Risks & Dependencies
- Dependency risk: YouTubei/NewPipe changes or rate limiting. Mitigation: modular data sources, caching, exponential backoff, and fallback endpoints where possible.
- Legal/compliance risk: streaming source terms may change. Mitigation: legal review before launch and the ability to disable remote streaming if needed.
- Performance risk: dynamic theming and large lists could cause jank. Mitigation: precompute palettes, pagination, and profiling.
- Battery risk: background playback and cache maintenance. Mitigation: adaptive update intervals and WorkManager constraints.
- Storage risk: downloads and cache growth. Mitigation: enforce cache limits and LRU eviction.

---

## 13. Open Questions
- What is the approved legal posture for YouTubei/NewPipe usage?
- Should we support any sign-in or cloud sync in the first public release?
- Do we need explicit regional availability or geo-restrictions at launch?
- What is the acceptable tradeoff between audio quality and data usage by default?
- Should local music playback be fully integrated into queue/shuffle or isolated?

---

## 14. Conclusion

This PRD provides a comprehensive blueprint for developing SonicMusic, a modern Android music streaming application. The document covers:

- **Complete feature specifications** for all 4 main pages (Home, Search, Library, Settings)
- **Detailed player implementation** (Mini & Full Player) with gesture controls
- **Robust backend architecture** using Clean Architecture, MVVM, and Media3
- **UI/UX guidelines** following Material 3 (Material You) design principles
- **Performance optimizations** and edge case handling
- **Testing strategy** and release checklist

**Key Differentiators:**
- Song-only focus (strict filtering of albums, DJ mixes, compilations)
- Dynamic Material You theming that adapts to album art
- Gesture-first player interactions
- Privacy-focused with local data storage
- Modern Jetpack Compose UI with smooth animations

**Development Timeline Estimate:**
- Phase 1 (Core Features): 8-10 weeks
- Phase 2 (Polish & Testing): 3-4 weeks
- Phase 3 (Beta & Launch): 2-3 weeks
- **Total:** ~14-17 weeks for v1.0 release

This PRD is ready for immediate use by a development team. Each section contains actionable requirements, code examples, and clear acceptance criteria for implementation.
