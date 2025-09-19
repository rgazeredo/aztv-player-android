# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AZTVPlayer is an Android TV application designed specifically for television environments. It's a video playlist player that fetches video content from a remote JSON API and plays them sequentially with TV-optimized controls.

**Key characteristics:**
- **Android TV focused**: Uses Leanback UI patterns and D-pad navigation
- **Video streaming**: Fetches videos from remote API (`https://az.tv.br/json/videos.json`)
- **Offline support**: Downloads videos for offline playback when internet is unavailable
- **Playlist playback**: Automatically plays videos in sequence with loop functionality
- **Dual caching**: Stream caching (500MB) + offline downloads (1GB)
- **No Compose**: Uses traditional Android Views (XML layouts)

## Development Commands

### Build Commands
```bash
# Clean and build debug APK
./gradlew clean assembleDebug

# Build release APK
./gradlew assembleRelease

# Install debug APK to connected device
./gradlew installDebug
```

### Testing Commands
```bash
# Run unit tests
./gradlew test

# Run connected/instrumentation tests
./gradlew connectedAndroidTest
```

### Lint and Quality
```bash
# Run lint checks
./gradlew lint

# Check for dependency updates
./gradlew dependencyUpdates
```

## Architecture

### Core Components

**MainActivity.kt** (`app/src/main/java/br/tv/az/player/MainActivity.kt`)
- Single Activity application
- Manages ExoPlayer instance and video playback
- Handles TV remote control input (D-pad, media keys)
- Implements video caching with 500MB limit
- Fetches video playlist from JSON API using OkHttp

**Video Data Model**
```kotlin
data class Video(
    val id: String,
    val url: String,
    val title: String,
    var localPath: String? = null,
    var isDownloaded: Boolean = false
)
```

### Key Libraries
- **ExoPlayer 2.19.1**: Video playback engine with caching and offline support
- **ExoPlayer Offline**: Download management for background video downloads
- **OkHttp 4.12.0**: HTTP client for API requests
- **AndroidX Leanback**: TV-specific UI components
- **Kotlin Coroutines**: Async operations

### Video Management
- **Online mode**: Videos loaded from `API_URL` constant, automatic downloads start
- **Offline mode**: Plays downloaded videos when no internet connection
- **Smart playback**: Automatically chooses offline/online source based on availability
- **Background downloads**: Videos download in background via `VideoDownloadService`
- Supports automatic progression through playlist
- Loop back to first video when reaching end
- Error handling with automatic skip to next video

### TV Interface
- **D-pad controls**: Left/Right for previous/next video, Center for play/pause
- **Media keys**: Standard TV remote media buttons supported
- **Overlay UI**: Shows video title, counter, and download status (auto-hides after 3 seconds)
- **Landscape orientation**: Locked landscape mode for TV viewing

### Caching Strategy
**Stream Cache:**
- Uses ExoPlayer's `SimpleCache` with LRU eviction
- 500MB cache size limit for streaming optimization
- Cache directory: `{app_cache_dir}/video_cache`

**Download Cache:**
- Permanent offline storage via `VideoDownloadManager`
- 1GB storage for downloaded videos
- Directory: `{external_files_dir}/offline_videos`
- Background downloads with progress tracking
- Automatic cleanup and storage management

## Package Structure
```
br.tv.az.player/
├── MainActivity.kt                  # Main activity and video player logic
├── VideoDownloadService.kt         # Background download service
├── VideoDownloadManager.kt         # Download management singleton
└── StandaloneDatabaseProvider.kt    # ExoPlayer database provider wrapper
```

## Key Configuration Files
- **Namespace**: `br.tv.az.player`
- **Min SDK**: 21 (Android 5.0)
- **Target SDK**: 34
- **Compile SDK**: 34
- **Java Version**: 17

## Development Notes

### When modifying video playback:
- Video URL format must be compatible with ExoPlayer
- Stream cache configuration is in `initializeCache()`
- Download cache configuration is in `initializeDownloadManager()`
- Buffer settings are optimized for streaming in `initializePlayer()`
- Offline/online source selection logic is in `playVideo()`

### When adding TV controls:
- Add key handling in `onKeyDown()` method
- Use D-pad constants (`KEYCODE_DPAD_*`)
- Consider overlay display timing (3-second auto-hide)

### When updating API integration:
- API endpoint is defined in `API_URL` constant
- Expected JSON format: `{"videos": [{"id": "", "url": "", "title": ""}]}`
- HTTP timeouts are set to 30 seconds
- Network availability checking via `isNetworkAvailable()`

### When managing downloads:
- Downloads are managed by `VideoDownloadManager` singleton
- Download progress tracked in `updateVideoDownloadStatus()`
- Background service `VideoDownloadService` handles downloads
- Downloads start automatically when videos are loaded online
- Offline fallback occurs when no internet connection detected

### Android TV Requirements:
- App requires `android.software.leanback` feature
- Uses `LEANBACK_LAUNCHER` intent category
- Landscape orientation enforced
- Hardware acceleration enabled
- Foreground service permission for background downloads