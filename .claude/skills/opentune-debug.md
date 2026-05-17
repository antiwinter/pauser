---
name: opentune-debug
description: Debug OpenTune on a connected Android TV device via the embedded HTTP debug API
---

## Setup

The OpenTune app runs an embedded HTTP server on fixed port 7920. Forward the port via ADB:

```sh
adb forward tcp:7920 tcp:7920
```

After forwarding, all commands below work against `http://localhost:7920`.

## Commands

### List all registered providers
```sh
curl http://localhost:7920/providers
```

### List configured servers (from DB)
```sh
curl http://localhost:7920/servers
```

### Add a server
```sh
curl -X POST http://localhost:7920/servers \
  -H 'Content-Type: application/json' \
  -d '{"protocol":"emby-kt","fields":{"url":"http://...","token":"..."}}'
```
Returns `{ "sourceId": "...", "displayName": "..." }` on success, or `{ "error": "..." }`.

### List active instances (same as configured servers)
```sh
curl http://localhost:7920/instances
```

### Browse root of an instance
```sh
curl "http://localhost:7920/instances/{sourceId}/browse"
```

### Browse a subfolder
```sh
curl "http://localhost:7920/instances/{sourceId}/browse?location=<ref>&start=0&limit=50"
```

### Get item detail
```sh
curl "http://localhost:7920/instances/{sourceId}/detail?ref=<itemRef>"
```

### Get playback spec (resolves stream URL)
```sh
curl "http://localhost:7920/instances/{sourceId}/playback?ref=<itemRef>&startMs=0"
```

### Search
```sh
curl "http://localhost:7920/instances/{sourceId}/search?scope=<location>&q=<query>"
```

### Navigate to player
```sh
curl -X POST http://localhost:7920/navigate \
  -H 'Content-Type: application/json' \
  -d '{"route":"player","provider":"emby-kt","sourceId":"<id>","itemRef":"<ref>","startMs":0}'
```

### Navigate home
```sh
curl -X POST http://localhost:7920/navigate \
  -H 'Content-Type: application/json' \
  -d '{"route":"home"}'
```

### Navigate to browse / detail
```sh
# browse
curl -X POST http://localhost:7920/navigate \
  -H 'Content-Type: application/json' \
  -d '{"route":"browse","provider":"emby-kt","sourceId":"<id>","itemRef":"<location>"}'

# detail
curl -X POST http://localhost:7920/navigate \
  -H 'Content-Type: application/json' \
  -d '{"route":"detail","provider":"emby-kt","sourceId":"<id>","itemRef":"<ref>"}'
```

## Typical debug workflow

1. `adb forward tcp:7920 tcp:7920`
2. `curl http://localhost:7920/servers` — find `sourceId` of the server to explore
3. `curl "http://localhost:7920/instances/<sourceId>/browse"` — get root items
4. Follow `ref` values to browse deeper folders
5. `curl "http://localhost:7920/instances/<sourceId>/playback?ref=<ref>"` — inspect stream URL
6. POST to `/navigate` with `route:"player"` to start playback on the TV
