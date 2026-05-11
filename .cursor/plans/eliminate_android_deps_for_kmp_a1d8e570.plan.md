---
name: Eliminate Android Dependencies from KMP Modules
overview: "Remove all Android and Media3 imports from provider-api, emby, and storage so they can be compiled as kotlin(\"jvm\") modules. Delete device-profile module — codec capabilities flow from app through setCapabilities() on OpenTuneProvider. Emby builds DeviceProfile internally, no Emby types leak outside :emby. Storage stays as a single module: Room KMP (2.6+) handles annotations, DataStore moves to :app, Context-dependent construction moves to OpenTuneApplication. Android-only code (ExoPlayer, Context, Build, Compose) stays in app or Android-only modules. App remains free to implement additional providers (like smb) against provider-api."
todos:
  - id: decouple-provider-api
    content: "Replace MediaSource/Uri/Context in provider-api with PlaybackUrlSpec/PlatformContext; fix resolveExternalSubtitle return type; remove MediaArt.DrawableRes; update all callers (EmbyProviderInstance, SmbProviderInstance, PlayerRoute.kt, player module, OpenTuneProviderRegistry)"
    status: pending
  - id: extract-emby-dtos-and-client
    content: "Verify all DTOs, EmbyRepository, EmbyPlaybackHooks, EmbyPlaybackUrlResolver, EmbyClientFactory, EmbyClientIdentification have zero Android deps"
    status: pending
  - id: add-capabilities-to-provider
    content: "Add setCapabilities(CodecCapabilities) to OpenTuneProvider in provider-api; delete device-profile module entirely"
    status: pending
  - id: decouple-storage
    content: "Extract AppConfigStore interface in storage; move DataStoreAppConfigStore to :app; replace Context-based Room builder with JVM file-path builder; move StorageBindings construction to OpenTuneApplication"
    status: pending
  - id: rename-modules-and-gradle
    content: "Rename modules per KMP architecture; switch shared modules to kotlin(\"jvm\"); update dependency graph"
    status: pending
isProject: false
---

# Eliminate Android Dependencies from KMP Modules

## Goal and mental model

```
                    KMP shared (pure Kotlin)
        ┌──────────────────────────────────────────┐
        │  :provider-api       contracts + DTOs    │
        │                      CodecCapabilities   │
        │  :emby               Emby provider (fully│
        │                      usable, no Android) │
        │  :storage            Room KMP (2.6+)     │
        │                      Context-free APIs   │
        └──────────────────────────────────────────┘
                      │  depends on provider-api
        ┌─────────────┼──────────────┬──────────────┐
        │             │              │              │
    :app (Android) :smb (Android)              :player (Android)
                     future:                     (ExoPlayer,
                     nfs, ftp,                   Compose TV)
                     drive, etc.
```

KMP provides **provider-api** (the contract), **emby** (a fully usable provider), and **storage** (Room with `expect/actual`). The app can implement additional providers (smb, nfs, etc.) by implementing `OpenTuneProvider`/`OpenTuneProviderInstance` from `provider-api`.

Modules that stay Android-only:
- `app` — UI, navigation, Compose TV
- `player` — ExoPlayer setup, Compose player screen
- `smb` — SMBJ-based SMB provider (implements provider-api)

---

## Step 1: Decouple provider-api from Android (merged)

This is the highest-risk step — it changes the contract that `player`, `emby`, `smb`, and the app all implement or depend on. Do it as one commit, verify the app builds and plays before continuing.

### 1a — PlaybackContracts.kt

**Remove** `OpenTuneMediaSourceFactory` (`fun interface` returning `MediaSource`) — gone entirely.

**Add** `PlaybackUrlSpec`:

```kotlin
data class PlaybackUrlSpec(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null, // e.g. "application/x-mpegURL" for HLS
)
```

**Replace** `PlaybackSpec`. Current fields `mediaSourceFactory: OpenTuneMediaSourceFactory` and `resolveExternalSubtitle: (suspend (String) -> Uri?)?` both use Android types. New shape:

```kotlin
data class PlaybackSpec(
    val urlSpec: PlaybackUrlSpec,
    val displayTitle: String,
    val durationMs: Long?,
    val hooks: OpenTunePlaybackHooks,
    val initialPositionMs: Long = 0L,
    val onPlaybackDispose: () -> Unit = {},
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    /**
     * Called by the player when selecting an external non-HTTP subtitle (SMB only).
     * Returns a local file path (or file:// string); Emby leaves this null.
     */
    val resolveExternalSubtitle: (suspend (subtitleRef: String) -> String?)? = null,
)
```

`resolveExternalSubtitle` return type changes from `android.net.Uri?` to `String?` — the player converts the path to a `Uri` locally inside `:player`.

**Add** `NoOpPlaybackHooks` in provider-api so SMB and future providers don't need to write a no-op:

```kotlin
object NoOpPlaybackHooks : OpenTunePlaybackHooks {
    override fun progressIntervalMs(): Long = 0L
    override suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float) = Unit
    override suspend fun onProgressTick(positionMs: Long, playbackRate: Float) = Unit
    override suspend fun onStop(positionMs: Long) = Unit
}
```

**`PlaybackContracts.kt` after:** Zero Android imports.

### 1e — CatalogContracts.kt

**Remove `MediaArt.DrawableRes`** from the sealed class. It carries an Android drawable resource ID, which is meaningless on any non-Android target.

```kotlin
sealed class MediaArt {
    data class Http(val url: String) : MediaArt()
    data class LocalFile(val absolutePath: String) : MediaArt()
    data object None : MediaArt()
}
```

Providers that previously returned `DrawableRes` should return `MediaArt.None`. The app layer shows whatever placeholder UI it chooses when it receives `None`.

### 1b — ProviderContracts.kt

Two signatures change:

```kotlin
interface OpenTuneProvider {
    // ...
    fun bootstrap(context: PlatformContext) {}   // was Context
}

interface OpenTuneProviderInstance {
    // ...
    suspend fun resolvePlayback(itemRef: String, startMs: Long): PlaybackSpec  // Context removed
}
```

### 1c — NEW PlatformContext.kt

**File:** `provider-api/src/main/java/com/opentune/provider/PlatformContext.kt`

`EmbyProvider.bootstrap` currently uses `Build.MODEL`, `Settings.Secure.ANDROID_ID`, and `PackageManager` — all Android. Replace with:

```kotlin
interface PlatformContext {
    val deviceName: String    // Build.MODEL on Android
    val deviceId: String      // Settings.Secure.ANDROID_ID on Android
    val clientVersion: String // from PackageManager on Android
}
```

Note: SHA-256 hashing in `EmbyProvider.bootstrap` uses `java.security.MessageDigest` — this is JVM standard, not Android-specific. No change needed there.

### 1d — Callers to update

| Caller | Change |
|---|---|
| `EmbyProviderInstance.resolvePlayback()` | Remove `context: Context` param; remove all Media3 imports; build and return `PlaybackUrlSpec(url, headers)` with Emby auth headers (`X-Emby-Token`) |
| `SmbProviderInstance.resolvePlayback()` | Remove `context: Context` param; build and return `PlaybackUrlSpec`; update `resolveExternalSubtitle` lambda to return `String?` (file path) instead of `Uri?` |
| `EmbyProvider.bootstrap()` | Switch to `PlatformContext`; logic unchanged |
| `SmbProvider.bootstrap()` | Update signature to `PlatformContext` (body stays no-op) |
| `PlayerRoute.kt` | Remove the `app` Context arg from `inst.resolvePlayback(itemRefDecoded, resumeMs, app)` |
| `player` module | Add a local `PlaybackUrlSpec.toMediaSource(): MediaSource` helper — create OkHttp client, attach headers, wrap in `OkHttpDataSource.Factory`, create `ProgressiveMediaSource`; update `resolveExternalSubtitle` consumer to call `Uri.parse(path)` |
| `OpenTuneProviderRegistry` | Add `AndroidPlatformContext(applicationContext)` construction; pass it to each provider's `bootstrap()` call |

**App-side** (in `:app`):

```kotlin
class AndroidPlatformContext(private val androidContext: Context) : PlatformContext {
    override val deviceName: String get() = Build.MODEL
    override val deviceId: String get() =
        Settings.Secure.getString(androidContext.contentResolver, Settings.Secure.ANDROID_ID)
    override val clientVersion: String get() = // PackageManager lookup
}
```

---

## Step 2: Verify emby module is Android-free (emby)

**Files to audit:**

| File | Expected state after Step 1 |
|---|---|
| `dto/*.kt` (6 files) | Already clean — pure `kotlinx.serialization` |
| `EmbyApi.kt` | Clean — Retrofit interface |
| `EmbyClientFactory.kt` | Clean — OkHttp + Retrofit, no Android |
| `EmbyClientIdentification.kt` | Clean — pure data class + string formatting |
| `EmbyRepository.kt` | Clean — calls EmbyApi, builds DeviceProfile request |
| `EmbyPlaybackHooks.kt` | Clean — calls EmbyRepository for progress reporting |
| `EmbyPlaybackUrlResolver.kt` | Clean — URL string manipulation |
| `EmbyProvider.kt` | Clean after Step 1 — uses PlatformContext |
| `EmbyProviderInstance.kt` | Clean after Step 1 — returns PlaybackUrlSpec |
| `EmbyHttpDiagnostics.kt` | Uses `android.util.Log` — replace all calls with `java.util.logging.Logger` |
| `EmbyImageUrls.kt` | Clean — URL building |
| `EmbyJson.kt` | Clean — Json configuration |
| `EmbyServerFieldsJson.kt` | Clean — serialization |

**Action:** Grep for `android.` imports across the module. In `EmbyHttpDiagnostics.kt`, replace every `android.util.Log.d/e/w/i(...)` call with the equivalent `java.util.logging.Logger.getLogger(...).fine/severe/warning/info(...)`.

**After:** The entire `:emby` module has zero Android imports.

---

## Step 3: Add capabilities to provider, delete device-profile module

**Principle:** No Emby keyword (`DeviceProfile`, `CodecProfile`, `DirectPlayProfile`, etc.) appears outside `:emby`. The `device-profile` module is deleted entirely.

**NEW `provider-api/src/main/java/com/opentune/provider/CodecCapabilities.kt`:**

From reading `AndroidDeviceProfileBuilder`, the only device-variable information it probes is: which video/audio MIME types are supported, and the max pixel count across AVC + HEVC decoders. Everything else (bitrate cap, containers, transcoding profiles, subtitle formats) is hardcoded inside the builder. `VideoCodecCapability` is therefore unnecessary — there is no per-codec resolution — and `modelName` is redundant with `PlatformContext.deviceName`.

```kotlin
data class CodecCapabilities(
    val supportedVideoMimeTypes: List<String>,  // e.g. ["video/avc", "video/hevc", "video/vp9"]
    val supportedAudioMimeTypes: List<String>,  // e.g. ["audio/mp4a-latm", "audio/ac3"]
    val maxVideoPixels: Int = 1920 * 1080,      // max supported resolution (width × height)
)
```

**Update `OpenTuneProvider` interface** in `provider-api/ProviderContracts.kt`:

```kotlin
interface OpenTuneProvider {
    // ... existing methods ...

    /** Set decoder capabilities probed from the platform. Default is no-op. */
    fun setCapabilities(capabilities: CodecCapabilities) {}
}
```

**`EmbyProvider` implementation:**

```kotlin
class EmbyProvider(...) : OpenTuneProvider {
    @Volatile
    private var capabilities: CodecCapabilities? = null

    override fun setCapabilities(capabilities: CodecCapabilities) {
        this.capabilities = capabilities
    }

    override fun createInstance(values: Map<String, String>): OpenTuneProviderInstance {
        val caps = capabilities ?: CodecCapabilities(
            supportedVideoMimeTypes = listOf("video/avc"),
            supportedAudioMimeTypes = listOf("audio/mp4a-latm"),
        )
        val deviceProfile = buildDeviceProfile(caps)  // private method, stays in :emby
        return EmbyProviderInstance(fields = values, deviceProfile = deviceProfile)
    }

    private fun buildDeviceProfile(caps: CodecCapabilities): DeviceProfile {
        // All mapping logic from current AndroidDeviceProfileBuilder:
        // MIME → Emby codec name, sqrtApprox for max resolution, hardcoded bitrate/containers/transcoding.
        // PlatformContext.deviceName provides Build.MODEL equivalent for DeviceIdentification.
    }
}
```

**`SmbProvider` implementation:** no-op default (doesn't care about codec capabilities).

**App-side probing:** The app probes `MediaCodecList` on Android (VideoToolbox on iOS), constructs `CodecCapabilities`, and calls `provider.setCapabilities(result)` on each provider. The app never imports `DeviceProfile`.

**Update `OpenTuneProviderRegistry`:** Currently `OpenTuneApplication` calls `AndroidDeviceProfileBuilder.build()` and passes the result to `OpenTuneProviderRegistry.default(deviceProfile: Any)`. After this step:
1. Remove the `deviceProfile` parameter from `OpenTuneProviderRegistry.default(...)`.
2. In `OpenTuneApplication`, replace `AndroidDeviceProfileBuilder.build()` with direct `MediaCodecList` probing that produces `CodecCapabilities`.
3. Call `provider.setCapabilities(caps)` on each registered provider after `bootstrap()`, inside the registry's initialization sequence.

**Delete:** `device-profile/` directory entirely.

---

## Step 4: Decouple storage module

Target plugin: `kotlin("jvm")` + Room KMP plugin. No `expect/actual` — Android-specific construction moves to `:app`, not into platform source sets.

**Files and what changes:**

| File | Current Android dep | Action |
|---|---|---|
| `ServerEntities.kt` | `@Entity` annotation | No change — Room KMP supports this under `kotlin("jvm")` |
| `Daos.kt` | `@Dao` annotation | No change — Room KMP supports this under `kotlin("jvm")` |
| `OpenTuneDatabase.kt` | `Context` in companion builder | Replace `Room.databaseBuilder(Context, ...)` with JVM Room builder taking a file path string: `Room.databaseBuilder<OpenTuneDatabase>(name = dbFilePath).build()` |
| `RoomMediaStateStore.kt` | None | Already clean |
| `MediaStateContracts.kt` | None | Already clean |
| `DataStoreAppConfigStore.kt` | `Context`, AndroidX DataStore | Extract `AppConfigStore` interface that stays in `:storage`; move `DataStoreAppConfigStore` class to `:app` |
| `ThumbnailDiskCache.kt` | None (uses `java.io.File` only) | Already JVM-portable — no change |
| `StorageBindings.kt` | `Context` | Accept `AppConfigStore` interface instead of `DataStoreAppConfigStore`; remove Context; construction moves to `OpenTuneApplication` |

**Concrete changes:**

1. **Extract `AppConfigStore` interface** (stays in `:storage`):
   ```kotlin
   interface AppConfigStore {
       // existing methods from DataStoreAppConfigStore
   }
   ```

2. **`DataStoreAppConfigStore`** moves to `:app` as the Android implementation of `AppConfigStore`. Logic unchanged.

3. **`OpenTuneDatabase.create()`** — replace Context-based builder:
   ```kotlin
   companion object {
       fun create(dbFilePath: String): OpenTuneDatabase =
           Room.databaseBuilder<OpenTuneDatabase>(name = dbFilePath).build()
   }
   ```
   `:app` computes `dbFilePath` via `context.getDatabasePath("opentune.db").absolutePath`.

4. **`StorageBindings`** — remove Context, accept `AppConfigStore`:
   ```kotlin
   class OpenTuneStorageBindings(
       val serverDao: ServerDao,
       val mediaStateStore: UserMediaStateStore,
       val appConfigStore: AppConfigStore,
       val thumbnailCache: ThumbnailDiskCache,
   )
   ```
   `OpenTuneApplication` constructs it, passing `DataStoreAppConfigStore(androidContext)` as the `appConfigStore`.

**No module split.** `:storage` stays as one module; all Android-specific wiring lives in `:app`.

---

## Step 5: Rename modules and update Gradle

**New `settings.gradle.kts`:**

```kotlin
rootProject.name = "OpenTune"

// Shared KMP modules (pure Kotlin — zero Android imports)
include(":provider-api")
include(":emby")
include(":storage")

// Android-only modules
include(":app")
include(":player")
include(":smb")
```

**Build plugins after refactor:**

| Module | Current plugin | After |
|---|---|---|
| `provider-api` | `android.library` | `kotlin("jvm")` |
| `emby` (was `providers:emby`) | `android.library` | `kotlin("jvm")` |
| `storage` (was `storage`) | `android.library` | `kotlin("jvm")` + Room KMP plugin |
| `app` | `android.application` | stays |
| `player` | `android.library` | stays |
| `smb` (was `providers:smb`) | `android.library` | stays |

**Dependency changes for shared modules (`:provider-api`, `:emby`, `:storage`):**

| Remove | Replace with |
|---|---|
| `kotlinx-coroutines-android` | `kotlinx-coroutines-core` (`Dispatchers.IO` is available on JVM) |
| `androidx.core.ktx` | remove — no Android APIs in shared modules |
| `media3.exoplayer`, `media3.datasource-okhttp` | remove from `:provider-api` and `:emby` |

**Dependency graph:**

```
app → provider-api, emby, storage, player, smb

player → provider-api, storage
smb → provider-api
emby → provider-api
storage → provider-api (for MediaStateKey using providerType)
```

**Physical directory changes:**

```
Before:                          After:
app/                             app/
providers/emby/                  emby/
providers/smb/                   smb/
device-profile/                  (deleted)
storage/                         storage/  (renamed internally, no split)
player/                          player/
provider-api/                    provider-api/
```

**Migration notes:**
- `providers/emby` → `emby/` (top-level, rename)
- `providers/smb` → `smb/` (top-level, rename)
- `storage/` stays in place, no split — just updates to Room KMP
- `provider-api/` stays in place but plugin changes
- `device-profile/` → deleted entirely

---

## Order of execution

1. **Step 1** — Decouple provider-api: replace `MediaSource`/`Uri`/`Context` throughout the contract layer and all callers (highest risk, foundational — one commit)
2. **Step 2** — Audit and clean remaining emby Android imports (depends on Step 1)
3. **Step 3** — Add `setCapabilities` to `OpenTuneProvider`, delete device-profile module
4. **Step 4** — Storage decoupling (independent of Step 3, can be done in parallel)
5. **Step 5** — Rename, restructure, update Gradle (depends on all above)

Steps 3 and 4 are independent of each other and can be done in parallel after Step 1.

---

## Risk and rollback

- **Step 1** changes the API that `player`, `emby`, and `smb` all depend on. Do this as one commit, verify the app builds and plays before moving on.
- **Step 2** is low risk — mostly removing imports and replacing one logging class.
- **Step 3** changes the `OpenTuneProvider` interface and deletes a module — all providers (emby, smb) and the registry need updating together.
- **Step 4** is medium risk — Room KMP with `kotlin("jvm")` is relatively new (2.6+); verify KSP/annotation processor works correctly. The DataStore move to `:app` is mechanical.
- **Step 5** is mechanical — renames and Gradle changes. Should be a single commit after everything else is verified.
- No data migration is needed. Room schema doesn't change.
- Each step can be committed independently within the ordering constraints.
