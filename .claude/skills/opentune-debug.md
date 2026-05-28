---
name: opentune-debug
description: Debug OpenTune on a connected Android TV device via the embedded HTTP debug API
---

## Setup

### Build & Deploy

```sh
# 1. Build TypeScript providers (bundled JS runs inside the app)
cd providers-ts && npm run build && cd ..

# 2. Build and install debug APK
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Forward debug API port
adb forward tcp:7920 tcp:7920
```

After forwarding, all commands below work against `http://localhost:7920`.

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
```sh
# player
curl -X POST http://localhost:7920/navigate \
  -H 'Content-Type: application/json' \
  -d '{"route":"player","provider":"catvod","endpointId":"<id>","itemRef":"<ref>","startMs":0}'

# browse
curl -X POST http://localhost:7920/navigate \
  -H 'Content-Type: application/json' \
  -d '{"route":"browse","provider":"catvod","endpointId":"<id>","itemRef":"<location>"}'

# detail
curl -X POST http://localhost:7920/navigate \
  -H 'Content-Type: application/json' \
  -d '{"route":"detail","provider":"catvod","endpointId":"<id>","itemRef":"<ref>"}'

# home
curl -X POST http://localhost:7920/navigate \
  -H 'Content-Type: application/json' \
  -d '{"route":"home"}'
```

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
curl "http://localhost:7920/debug/media-state/catvod/<endpointId>/<itemId>"

# Set subtitle track
curl -X POST http://localhost:7920/debug/media-state/subtitle-track \
  -H 'Content-Type: application/json' \
  -d '{"protocol":"catvod","endpointId":"<id>","itemId":"<id>","trackId":"<id>"}'

# Set audio track
curl -X POST http://localhost:7920/debug/media-state/audio-track \
  -H 'Content-Type: application/json' \
  -d '{"protocol":"catvod","endpointId":"<id>","itemId":"<id>","trackId":"<id>"}'
```

## Typical debug workflow

1. `adb forward tcp:7920 tcp:7920`
2. `curl http://localhost:7920/endpoints` — find `endpointId` of the server to explore
3. `curl "http://localhost:7920/clients/<endpointId>/browse"` — get root items
4. Follow `ref` values to browse deeper folders
5. `curl "http://localhost:7920/clients/<endpointId>/playback?ref=<ref>"` — inspect stream URL
6. POST to `/navigate` with `route:"player"` to start playback on the TV
