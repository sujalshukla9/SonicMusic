# Sonic Music Performance Profiling Checklist

Use this checklist before release to validate smoothness on a real device.

## 1. Test Setup

- Use a physical device (not emulator) with developer options enabled.
- Enable 60Hz mode (or note refresh rate if 90/120Hz).
- Close background apps and disable battery saver.
- Build and install debug APK:

```bash
./gradlew :app:installDebug
```

Package name used below: `com.sonicmusic.app`

## 2. Core Scenarios (Run 3 Times Each)

### Home screen

- Open app cold start.
- Scroll Home continuously for 30-45s.
- Swipe the Listen Again pager repeatedly.
- Open and close mini/full player once, return to Home.

### Search screen

- Type query quickly (`arijit`, `weeknd`, `lofi`) and erase/retype.
- Open results, scroll to trigger pagination.
- Tap a result and return.

### FullPlayer screen

- Open full player while song is playing.
- Drag seekbar repeatedly for 20s.
- Toggle play/pause, next/previous, queue sheet open/close.
- Keep player open for 60s.

### Offline scenario

- Disable Wi-Fi/mobile data.
- Launch app and navigate Home/Search/FullPlayer.
- Try download, search, and playback resume from network-only song.
- Confirm no crash and clear error messages.

## 3. Frame/Jank Capture

Reset frame stats before each scenario:

```bash
adb shell dumpsys gfxinfo com.sonicmusic.app reset
```

Run scenario, then capture:

```bash
adb shell dumpsys gfxinfo com.sonicmusic.app > /tmp/sonic_gfxinfo.txt
adb shell dumpsys gfxinfo com.sonicmusic.app framestats > /tmp/sonic_framestats.txt
```

## 4. Memory Capture

Capture memory at scenario start and end:

```bash
adb shell dumpsys meminfo com.sonicmusic.app > /tmp/sonic_meminfo_start.txt
adb shell dumpsys meminfo com.sonicmusic.app > /tmp/sonic_meminfo_end.txt
```

## 5. Pass/Fail Targets

- No ANR, no crash in any scenario.
- Janky frames: ideally `< 5%`, acceptable `< 8%`.
- 90th percentile frame time:
  - 60Hz device: `< 16.6ms` ideal, `< 24ms` acceptable.
  - 120Hz device: `< 8.3ms` ideal, `< 16ms` acceptable.
- No sustained memory growth trend across 3 loops.
- Offline actions should fail gracefully with user-readable messages.

## 6. Regression Notes Template

Record these after each run:

- Device + Android version:
- Build commit/hash:
- Scenario:
- Janky frames %:
- P95/P99 frame time:
- Memory delta (PSS):
- Visible lag points:
- Crash/ANR:
- Fix required:
