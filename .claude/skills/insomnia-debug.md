---
name: insomnia-debug
description: Debug Insomnia on a connected Android TV device via the embedded HTTP debug API
---

## Setup

### Prerequisites

- **Android Studio** installed (provides SDK, build tools, and JDK via JBR)
- **JDK** installed (Temurin recommended). On Windows with Chocolatey: `choco install temurin`
- **Android SDK** platform 35 and build-tools 37.0.0 installed
- **quickjs_ng** submodule initialized: `git submodule update --init` or clone manually into `content/providers/js/src/main/jni/quickjs_ng`

### Build & Deploy

#### Windows

```sh
# Set PATH to JDK (adjust path to match your installation)
export PATH="/c/Program Files/Eclipse Adoptium/jdk-21.0.8.9-hotspot/bin:$PATH"

# 1. Build TypeScript providers (bundled JS runs inside the app)
cd providers-ts && npm run build && cd ..

# 2. Build and install debug APK on the target device
./gradlew :app:assembleDebug
adb -s 192.168.17.56:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Forward debug API port
adb -s 192.168.17.56:5555 forward tcp:7920 tcp:7920

# 4. Launch the app
adb -s 192.168.17.56:5555 shell monkey -p com.insomnia.app -c android.intent.category.LEANBACK_LAUNCHER -c android.intent.category.LAUNCHER 1
```

#### Linux/Mac

```sh
# 1. Build TypeScript providers (bundled JS runs inside the app)
cd providers-ts && npm run build && cd ..

# 2. Build and install debug APK
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Forward debug API port
adb forward tcp:7920 tcp:7920

# 4. Launch the app
adb shell monkey -p com.insomnia.app -c android.intent.category.LEANBACK_LAUNCHER -c android.intent.category.LAUNCHER 1
```

After forwarding, all commands below work against `http://localhost:7920`.

### Windows Build Notes
- JDK must be on PATH before running `npm run build`

## Commands

### List all registered providers
```sh
curl http://localhost:7920/providers
```

### List configured endpoints
```sh
curl http://localhost:7920/endpoints
```
Returns `[{"endpointId":"...","protocol":"...","displayName":"..."}, ...]`.

### List active clients (same data as endpoints, for test harness compat)
```sh
curl http://localhost:7920/clients
```

### Add an endpoint
```sh
curl -X POST http://localhost:7920/endpoints \
  -H 'Content-Type: application/json' \
  -d '{"protocol":"catvod","fields":{"config_url":"http://..."}}'
```
Runs `provider.test()` before saving. Returns `{ "endpointId": "...", "displayName": "..." }` on success, or `{ "error": "..." }` on failure.

### Delete an endpoint
```sh
curl -X DELETE http://localhost:7920/endpoints/<endpointId>
```

### Browse root or a subfolder
```sh
# Root
curl "http://localhost:7920/clients/<endpointId>/browse"

# Subfolder
curl "http://localhost:7920/clients/<endpointId>/browse?location=<ref>&start=0&limit=50"
```

### Get item detail
```sh
curl "http://localhost:7920/clients/<endpointId>/detail?ref=<itemRef>"
```

### Get playback spec (resolves stream URL)
```sh
curl "http://localhost:7920/clients/<endpointId>/playback?ref=<itemRef>&startMs=0"
```

### Search
```sh
curl "http://localhost:7920/clients/<endpointId>/search?scope=<location>&q=<query>"
```

### Navigate to player / browse / detail

`provider` in the body is optional and ignored — protocol is derived from `endpointId` (`"${protocol}_${hash}"`).

```sh
# player (overlay — no route navigation)
curl -X POST http://localhost:7920/navigate \
  -H 'Content-Type: application/json' \
  -d '{"route":"player","endpointId":"<id>","itemRef":"<ref>","startMs":0}'

# browse
curl -X POST http://localhost:7920/navigate \
  -H 'Content-Type: application/json' \
  -d '{"route":"browse","endpointId":"<id>","itemRef":"<location>"}'

# detail
curl -X POST http://localhost:7920/navigate \
  -H 'Content-Type: application/json' \
  -d '{"route":"detail","endpointId":"<id>","itemRef":"<ref>"}'

# home
curl -X POST http://localhost:7920/navigate \
  -H 'Content-Type: application/json' \
  -d '{"route":"home"}'
```

### Seek during playback

Precise seek (absolute or relative) without the ±15s DPAD quantization. Use this to land
exactly on a cached byte range when debugging seek-back latency.

```sh
# Absolute position
curl -X POST http://localhost:7920/debug/seek \
  -H 'Content-Type: application/json' \
  -d '{"positionMs":28000}'

# Relative (e.g. -2s from current position)
curl -X POST http://localhost:7920/debug/seek \
  -H 'Content-Type: application/json' \
  -d '{"deltaMs":-2000}'
```

Returns `{"ok":true}`. One of `positionMs` / `deltaMs` must be present. Seek fires through the
same `PlaybackSession.seekTo` path as the DPAD keys, so the `sd:` diagnostics timeline applies.

### JAR bridge (proxy host.jar calls from Node test harness)
```sh
curl -X POST http://localhost:7920/debug/jar \
  -H 'Content-Type: application/json' \
  -d '{"name":"load","args":"<json>"}'
```

### Subtitle preferences
```sh
# Get
curl http://localhost:7920/debug/subtitle-prefs

# Set
curl -X POST http://localhost:7920/debug/subtitle-prefs \
  -H 'Content-Type: application/json' \
  -d '{"offsetFraction":0.0,"sizeScale":1.0}'
```

### Media state (playback position, subtitle/audio tracks)
```sh
# List all states for an endpoint
curl "http://localhost:7920/debug/media-state?protocol=catvod&endpointId=<id>"

# Get state for a specific item
curl "http://localhost:7920/debug/media-state/<endpointId>/<itemRef>"

# Set subtitle track
curl -X POST http://localhost:7920/debug/media-state/subtitle-track \
  -H 'Content-Type: application/json' \
  -d '{"endpointId":"<id>","itemRef":"<ref>","trackId":"<id>"}'

# Set audio track
curl -X POST http://localhost:7920/debug/media-state/audio-track \
  -H 'Content-Type: application/json' \
  -d '{"endpointId":"<id>","itemRef":"<ref>","trackId":"<id>"}'
```

## Driving playback with remote keys (seek / menu / subtitle)

The debug API has no live seek/subtitle-select endpoint. Drive the on-screen player with adb
key events (the TV `InsomniaTvPlayerView` owns these keycodes):

| Action | keyevent | adb command |
| --- | --- | --- |
| Seek +15s | 22 (DPAD_RIGHT) | `adb shell input keyevent 22` |
| Seek −15s | 21 (DPAD_LEFT) | `adb shell input keyevent 21` |
| Open menu | 82 (MENU) | `adb shell input keyevent 82` |
| Navigate up/down | 19 / 20 | `adb shell input keyevent 20` |
| Select / enter | 23 (DPAD_CENTER) | `adb shell input keyevent 23` |
| Play/pause | 85 | `adb shell input keyevent 85` |

Menu order is: **Subtitles → Adjust position & size → Audio track → Playback speed**. To pick a
subtitle: `82` (open) → `23` (enter Subtitles) → `20`×N (down to the track) → `23` (select).
Verify focus with a screenshot before selecting — read it back as a Windows path, not `/tmp`:

```sh
adb -s <serial> exec-out screencap -p > "C:/Users/warits/code/insomnia/_shot.png"   # then Read it; delete when done
```

## Bandwidth / playback throughput diagnosis

`BandwidthTracker` (player module) counts bytes via an OkHttp interceptor. `PlaybackSurface`'s
1 Hz poll logs a per-second timeline under tag **`OT_BW`** (debug builds):

```sh
adb -s <serial> logcat -c                       # clear first
adb -s <serial> logcat OT_BW:I OT_Subtitle:D InsomniaTvPlayerKeys:D PlaybackSession:D '*:S'
# each line: mbps=.. deltaKB=.. totalMB=.. pos=..ms buffered=..ms state=..
#   state: 1=IDLE  2=BUFFERING  3=READY/playing  4=ENDED
```

Reading the timeline:
- `totalMB` frozen while `state=3` and playback advances → bytes flowing through an **untracked**
  OkHttp client (interceptor missing on that source path), not a real stall.
- `deltaKB=0` with `state=2` → a **real** stall (origin seek latency or server throttle).
- `buffered` shrinking while `state=3` → download rate is below the content bitrate; a stall is coming.
- `state=1` after a seek → check `logcat … ExoPlayer:E` for a `Source error` (e.g. MatroskaExtractor
  `ArrayIndexOutOfBoundsException` on backward/deep seeks over a flaky range server).

## Typical debug workflow

1. `adb forward tcp:7920 tcp:7920`
2. `curl http://localhost:7920/endpoints` — find `endpointId` of the server to explore
3. `curl "http://localhost:7920/clients/<endpointId>/browse"` — get root items
4. Follow `ref` values to browse deeper folders
5. `curl "http://localhost:7920/clients/<endpointId>/playback?ref=<ref>"` — inspect stream URL
6. POST to `/navigate` with `route:"player"` to start playback on the TV
