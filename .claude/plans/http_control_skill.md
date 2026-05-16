# Plan: OpenTune Debug Server + Claude Code Skill

## Context

Debugging OpenTune on an Android TV requires physically operating the remote to navigate menus and trigger playback. The goal is to expose the app's provider, instance, and navigation APIs over HTTP so Claude Code (or any HTTP client) can drive the app remotely — listing providers, browsing catalogs, resolving playback specs, and navigating to the player — without touching the remote.

This is done by:
1. Moving `OpenTuneServer` from `:app` into a new `:server` Android library module
2. Extending it with a debug API (provider/instance/catalog/navigation routes)
3. Writing a Claude Code skill that knows how to call these routes

---

## Module Restructuring: New `:server` Android Library

**Create** `server/` as a new Android library module.

### `server/build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.opentune.server"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    api(project(":contracts"))
    // Ktor CIO (same versions already in :app)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.core)
    // kotlinx-serialization for JSON responses
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
```

### Files to create inside `:server`
```
server/src/main/java/com/opentune/server/
  OpenTuneServer.kt       ← moved from :app, logging abstracted (see below)
  DebugRoutes.kt          ← new debug API routes
  DebugModels.kt          ← JSON response data classes
  NavigationBridge.kt     ← singleton for app navigation commands
```

### Logging abstraction
`OpenTuneServer` currently calls `android.util.Log` directly. The `:server` module IS an Android library so this is fine — no abstraction needed.

---

## New Debug API Routes (in `DebugRoutes.kt`)

All routes return `application/json`. Add a `installDebugRoutes(...)` extension on `Application` (Ktor).

```
GET  /providers
     → list all registered providers: protocol, display name, fields spec

GET  /servers
     → list all configured server entities (sourceId, protocol, displayName)

POST /servers
     body: { protocol, fields: { key: value } }
     → call provider.validateFields(), if success call serverDao.insert(ServerEntity)
     → returns { sourceId, displayName } or { error }

GET  /instances
     → list active instanceRegistry entries

GET  /instances/{sourceId}/browse?location=&start=0&limit=50
     → call instance.listEntry(location, start, limit)
     → returns EntryList as JSON

GET  /instances/{sourceId}/detail?ref=
     → call instance.getDetail(itemRef)
     → returns EntryDetail as JSON

GET  /instances/{sourceId}/search?scope=&q=
     → call instance.search(scope, query)
     → returns List<EntryInfo> as JSON

GET  /instances/{sourceId}/playback?ref=&startMs=0
     → call instance.getPlaybackSpec(itemRef, startMs)
     → returns PlaybackSpec as JSON (url, headers, mimeType, title, durationMs)

POST /navigate
     body: { route: "player" | "browse" | "detail" | "home", provider?, sourceId?, itemRef?, startMs? }
     → pushes navigation command into NavigationBridge channel
     → app's NavHost collects and calls nav.navigate(...)
     → returns { ok: true }
```

The existing `/stream/{token}` route stays exactly as-is.

---

## `NavigationBridge.kt`

A simple global singleton — same pattern as `StreamRegistrarHolder`.

```kotlin
object NavigationBridge {
    val commands = Channel<NavCommand>(Channel.BUFFERED)
}

sealed class NavCommand {
    object Home : NavCommand()
    data class Browse(val provider: String, val sourceId: String, val location: String?) : NavCommand()
    data class Detail(val provider: String, val sourceId: String, val itemRef: String) : NavCommand()
    data class Player(val provider: String, val sourceId: String, val itemRef: String, val startMs: Long = 0) : NavCommand()
}
```

In `OpenTuneNavHost.kt`, add a `LaunchedEffect` that collects `NavigationBridge.commands` and calls `nav.navigate(Routes.xxx(...))`.

---

## Wiring in `:app`

### `OpenTuneApplication.kt`
- Remove the direct construction of `OpenTuneServer` (it now lives in `:server`)
- Pass `providerRegistry`, `instanceRegistry`, and `storageBindings.serverDao` to `OpenTuneServer` (or to the debug routes installer)
- Add `implementation(project(":server"))` to `app/build.gradle.kts`; remove Ktor deps from `:app` (they move to `:server`)

### `OpenTuneServer.kt` (in `:server`)
Constructor gains two optional parameters used by debug routes:
```kotlin
class OpenTuneServer(
    private val providerRegistry: OpenTuneProviderRegistry? = null,
    private val instanceRegistry: ProviderInstanceRegistry? = null,
    private val serverDao: ServerDao? = null,
) : StreamRegistrar
```
When non-null, the Ktor routing block calls `installDebugRoutes(...)`.

### `settings.gradle.kts`
Add: `include(":server")`

---

## JSON Models (`DebugModels.kt`)

```kotlin
@Serializable data class ProviderDto(val protocol: String, val fields: List<FieldDto>)
@Serializable data class FieldDto(val id: String, val label: String, val kind: String, val required: Boolean, val sensitive: Boolean)
@Serializable data class ServerDto(val sourceId: String, val protocol: String, val displayName: String)
@Serializable data class AddServerRequest(val protocol: String, val fields: Map<String, String>)
@Serializable data class AddServerResponse(val sourceId: String? = null, val displayName: String? = null, val error: String? = null)
@Serializable data class EntryInfoDto(val ref: String, val title: String, val type: String, val coverUrl: String? = null)
@Serializable data class PlaybackSpecDto(val url: String, val mimeType: String?, val title: String?, val durationMs: Long, val headers: Map<String, String>)
@Serializable data class NavigateRequest(val route: String, val provider: String? = null, val sourceId: String? = null, val itemRef: String? = null, val startMs: Long = 0)
```

---

## Claude Code Skill (`.claude/skills/opentune-debug.md`)

```markdown
---
name: opentune-debug
description: Debug OpenTune on a connected Android TV device via the embedded HTTP debug API
---

## Setup
The OpenTune app runs an embedded HTTP server. Forward the port via ADB:
  adb forward tcp:9090 tcp:<port>

The port is logged at app startup: grep "OpenTuneServer started on port" in logcat.
After forwarding, base URL is http://localhost:9090.

## Commands

List providers:
  curl http://localhost:9090/providers

List configured servers:
  curl http://localhost:9090/servers

Add a server:
  curl -X POST http://localhost:9090/servers \
    -H 'Content-Type: application/json' \
    -d '{"protocol":"emby-kt","fields":{"url":"http://...","token":"..."}}'

List active instances:
  curl http://localhost:9090/instances

Browse root of an instance:
  curl "http://localhost:9090/instances/{sourceId}/browse"

Browse a folder:
  curl "http://localhost:9090/instances/{sourceId}/browse?location=<ref>&start=0&limit=50"

Get item detail:
  curl "http://localhost:9090/instances/{sourceId}/detail?ref=<itemRef>"

Get playback spec:
  curl "http://localhost:9090/instances/{sourceId}/playback?ref=<itemRef>&startMs=0"

Search:
  curl "http://localhost:9090/instances/{sourceId}/search?scope=<location>&q=<query>"

Navigate to player:
  curl -X POST http://localhost:9090/navigate \
    -H 'Content-Type: application/json' \
    -d '{"route":"player","provider":"emby-kt","sourceId":"<id>","itemRef":"<ref>","startMs":0}'

Navigate home:
  curl -X POST http://localhost:9090/navigate -d '{"route":"home"}'
```

---

## Files to Create / Modify

| Action | File |
|--------|------|
| CREATE | `server/build.gradle.kts` |
| CREATE | `server/src/main/AndroidManifest.xml` |
| CREATE | `server/src/main/java/com/opentune/server/OpenTuneServer.kt` |
| CREATE | `server/src/main/java/com/opentune/server/DebugRoutes.kt` |
| CREATE | `server/src/main/java/com/opentune/server/DebugModels.kt` |
| CREATE | `server/src/main/java/com/opentune/server/NavigationBridge.kt` |
| CREATE | `.claude/skills/opentune-debug.md` |
| MODIFY | `settings.gradle.kts` — add `include(":server")` |
| MODIFY | `app/build.gradle.kts` — swap Ktor deps for `implementation(project(":server"))` |
| MODIFY | `app/src/main/java/com/opentune/app/OpenTuneApplication.kt` — pass registries to server constructor |
| MODIFY | `app/src/main/java/com/opentune/app/navigation/OpenTuneNavHost.kt` — collect NavigationBridge commands |
| DELETE | `app/src/main/java/com/opentune/app/server/OpenTuneServer.kt` |

---

## Verification

1. Build and install the app — ensure no compilation errors
2. Run `adb logcat | grep OpenTuneServer` to find the port, then `adb forward tcp:9090 tcp:<port>`
3. `curl http://localhost:9090/providers` — should return JSON list of providers
4. `curl http://localhost:9090/servers` — should return configured servers from DB
5. Browse an instance: `curl "http://localhost:9090/instances/<sourceId>/browse"`
6. Navigate to player via `POST /navigate` and verify the TV navigates to the player screen
7. Confirm `/stream/{token}` still works (existing SMB playback unaffected)
