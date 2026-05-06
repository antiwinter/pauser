---
name: Eliminate Android Dependencies from KMP Modules
overview: "Remove all Android and Media3 imports from provider-api, emby, device-profile, and storage so they can be compiled as KMP commonMain. Room KMP (2.6+) means storage stays as a single module with expect/actual for platform-specific database creation. KMP modules provide fully functional providers (emby). Android-only code (ExoPlayer, Context, Build, Compose) stays in app or Android-only modules. App remains free to implement additional providers (like smb) against provider-api."
todos:
  - id: decouple-playback-contracts
    content: "Replace Media3 MediaSource in provider-api/PlaybackContracts with platform-agnostic PlaybackUrlSpec; add NoOpPlaybackHooks"
    status: pending
  - id: decouple-provider-instance
    content: "Remove Context from resolvePlayback; remove all Media3/OkHttp construction from EmbyProviderInstance; return PlaybackUrlSpec with headers"
    status: pending
  - id: decouple-emby-provider
    content: "Replace bootstrap(Context) with bootstrap(PlatformContext); introduce PlatformContext interface in provider-api; app provides AndroidPlatformContext"
    status: pending
  - id: extract-emby-dtos-and-client
    content: "Verify all DTOs, EmbyRepository, EmbyPlaybackHooks, EmbyPlaybackUrlResolver, EmbyClientFactory, EmbyClientIdentification have zero Android deps"
    status: pending
  - id: decouple-device-profile
    content: "Split AndroidDeviceProfileBuilder: shared DeviceProfileBuilder(CodecCapabilities) + expect fun probeCodecCapabilities()"
    status: pending
  - id: decouple-storage
    content: "Remove Android Context/DataStore from storage; use expect/actual for database creation and preferences; Room 2.6+ KMP support keeps it as a single module"
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
        │  :emby               Emby provider (fully│
        │                      usable, no Android) │
        │  :device-profile     codec probe expect  │
        │                      + shared mapping    │
        │  :storage            Room KMP (2.6+)     │
        │                      expect/actual for   │
        │                      DB creation         │
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

## Step 1: Decouple playback contracts (provider-api)

**File:** `provider-api/src/main/java/com/opentune/provider/PlaybackContracts.kt`

**Problem:** `OpenTuneMediaSourceFactory` returns `androidx.media3.exoplayer.source.MediaSource`.

**Change:** Replace with a platform-agnostic playback URL descriptor:

```kotlin
data class PlaybackUrlSpec(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null, // e.g. "application/x-mpegURL" for HLS
)
```

Update `PlaybackSpec`:

```kotlin
data class PlaybackSpec(
    val urlSpec: PlaybackUrlSpec,
    val urlSpecFallback: PlaybackUrlSpec? = null,
    val displayTitle: String,
    val durationMs: Long?,
    val audioFallbackOnly: Boolean,
    val hooks: OpenTunePlaybackHooks,
    val audioDecodeUnsupportedBanner: String? = null,
    val initialPositionMs: Long = 0L,
    val onPlaybackDispose: () -> Unit = {},
)
```

**Add `NoOpPlaybackHooks`** in provider-api so app-provided providers (like SMB) don't need to write a no-op class:

```kotlin
object NoOpPlaybackHooks : OpenTunePlaybackHooks {
    override fun progressIntervalMs(): Long = 0L
    override suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float) = Unit
    override suspend fun onProgressTick(positionMs: Long, playbackRate: Float) = Unit
    override suspend fun onStop(positionMs: Long) = Unit
}
```

**Remove:** `OpenTuneMediaSourceFactory` — no longer in `provider-api`.

**Callers to update:**
- `EmbyProviderInstance.resolvePlayback()` — build `PlaybackUrlSpec` instead of `ProgressiveMediaSource.Factory`.
- `SmbProviderInstance.resolvePlayback()` — build `PlaybackUrlSpec` instead of `ProgressiveMediaSource.Factory`. Use `NoOpPlaybackHooks` instead of `SmbPlaybackHooks` (or keep `SmbPlaybackHooks` in the `smb` module if it needs custom behavior).
- `player` module — add a local helper that converts `PlaybackUrlSpec` → Media3 `MediaSource`. This is a thin conversion: create OkHttp client, attach headers, wrap in `OkHttpDataSource.Factory`, create `ProgressiveMediaSource`.

**`PlaybackContracts.kt` after:** Zero Android imports.

---

## Step 2: Decouple EmbyProviderInstance (emby)

**File:** `providers/emby/src/main/java/com/opentune/emby/api/EmbyProviderInstance.kt`

**Problem:** `resolvePlayback` takes `Context` (line 108) and constructs OkHttp + `ProgressiveMediaSource` + `MediaItem` directly — all Android/Media3.

**Change:**
1. Remove `context: Context` parameter from `resolvePlayback`.
2. Remove all Media3 imports (`OkHttpDataSource`, `ProgressiveMediaSource`, `MediaItem`, `Uri`).
3. Remove OkHttp `DataSource` construction. Build `PlaybackUrlSpec` with the resolved URL and Emby auth headers (`X-Emby-Token`).
4. Use `EmbyPlaybackHooks` for session reporting (stays — it has zero Android deps).
5. `onPlaybackDispose` closes the OkHttp client if needed, or stays no-op since HTTP is stateless.

**After:** `EmbyProviderInstance` imports only `kotlinx.coroutines`, `kotlinx.serialization`, and `provider-api` contracts. Zero Android.

---

## Step 3: PlatformContext interface (provider-api)

**File:** NEW `provider-api/src/main/java/com/opentune/provider/PlatformContext.kt`

**Problem:** `EmbyProvider.bootstrap(context: Context)` (line 93 of `EmbyProvider.kt`) uses `Build.MODEL`, `Settings.Secure.ANDROID_ID`, `PackageManager`. `SmbProvider.bootstrap` is a no-op but still takes `Context`.

**Change:** Introduce a platform-agnostic context interface in `provider-api`:

```kotlin
interface PlatformContext {
    val deviceName: String      // Build.MODEL (Android) / UIDevice.model (iOS)
    val deviceId: String        // Settings.Secure.ANDROID_ID / identifierForVendor
    val clientVersion: String   // package version
}

// Update OpenTuneProvider interface
interface OpenTuneProvider {
    // ... existing methods ...

    /** Called once before any provider method is used. */
    fun bootstrap(context: PlatformContext) {}
}
```

**Update `EmbyProvider.bootstrap(PlatformContext)`:**

```kotlin
override fun bootstrap(context: PlatformContext) {
    EmbyClientIdentificationStore.install(
        EmbyClientIdentification(
            clientName = "OpenTune",
            deviceName = context.deviceName,
            deviceId = context.deviceId,
            clientVersion = context.clientVersion,
        ),
    )
}
```

**App-side implementation** (in `app` module, Android-only):

```kotlin
class AndroidPlatformContext(private val androidContext: Context) : PlatformContext {
    override val deviceName: String get() = Build.MODEL
    override val deviceId: String get() =
        Settings.Secure.getString(androidContext.contentResolver, Settings.Secure.ANDROID_ID)
    override val clientVersion: String get() = ... // PackageManager
}
```

**`OpenTuneProviderRegistry`** passes `AndroidPlatformContext(applicationContext)` to each provider during initialization.

**`PlatformContext` after:** In `provider-api`. Zero Android imports. The interface only exposes `String` properties.

---

## Step 4: Verify emby module is Android-free (emby)

**Files to audit:**

| File | Expected state after Steps 1-3 |
|---|---|
| `dto/*.kt` (6 files) | Already clean — pure `kotlinx.serialization` |
| `EmbyApi.kt` | Clean — Retrofit interface |
| `EmbyClientFactory.kt` | Clean — OkHttp + Retrofit, no Android |
| `EmbyClientIdentification.kt` | Clean — pure data class + string formatting |
| `EmbyRepository.kt` | Clean — calls EmbyApi, builds DeviceProfile request |
| `EmbyPlaybackHooks.kt` | Clean — calls EmbyRepository for progress reporting |
| `EmbyPlaybackUrlResolver.kt` | Clean — URL string manipulation |
| `EmbyProvider.kt` | Clean after Step 3 — uses PlatformContext |
| `EmbyProviderInstance.kt` | Clean after Step 2 — returns PlaybackUrlSpec |
| `EmbyHttpDiagnostics.kt` | Check for Android-specific logging — remove if needed |
| `EmbyImageUrls.kt` | Clean — URL building |
| `EmbyJson.kt` | Clean — Json configuration |
| `EmbyServerFieldsJson.kt` | Clean — serialization |

**Action:** Read each file, grep for `android.` imports. If `EmbyHttpDiagnostics.kt` has Android-specific code, remove it or inline the relevant parts into `EmbyClientFactory`.

**After:** The entire `:emby` module has zero Android imports and is directly usable as a KMP commonMain module.

---

## Step 5: Decouple device-profile

**File:** `device-profile/src/main/java/com/opentune/deviceprofile/AndroidDeviceProfileBuilder.kt`

**Problem:** Entire file uses `android.media.MediaCodecList`, `MediaFormat`, `android.os.Build`.

**Change:** Split into two parts.

**Part A — Shared `DeviceProfileBuilder` (pure Kotlin):**

```kotlin
data class VideoCodecCapability(
    val mimeType: String,   // e.g. "video/avc"
    val maxWidth: Int,
    val maxHeight: Int,
)

data class CodecCapabilities(
    val videoCodecs: List<VideoCodecCapability>,
    val audioCodecs: List<String>,  // MIME types like "audio/mp4a-latm"
)

object DeviceProfileBuilder {
    fun build(capabilities: CodecCapabilities, modelName: String = ""): DeviceProfile {
        // All current mapping logic refactored to take data instead of probing:
        // - MIME → Emby codec name mapping
        // - Profile condition building (bitrate, width, height limits)
        // - Transcoding profiles, subtitle profiles, response profiles
        // - DeviceIdentification with modelName
    }
}
```

**Part B — `expect` function for probing:**

```kotlin
// In commonMain (device-profile)
expect fun probeCodecCapabilities(): CodecCapabilities
```

```kotlin
// In androidMain (device-profile) — later, when converting to KMP
actual fun probeCodecCapabilities(): CodecCapabilities {
    val list = MediaCodecList(MediaCodecList.ALL_CODECS)
    // ... current isDecoderSupported + maxVideoPixels logic ...
}
```

For now (before full KMP setup), keep the `actual` implementation in the same module as an `android/` subpackage. Once the module is converted to KMP, move it to `androidMain`.

**Shared `DeviceProfileBuilder.build()`** contains ~80% of the current file. The probe is thin hardware interrogation.

---

## Step 6: Decouple storage module (Room KMP)

Room 2.6+ supports KMP. `@Entity`, `@Dao`, `@Database` annotations work in `commonMain`. The compiler generates platform-specific implementations. The only Android-specific code is the `Context`-dependent database builder and DataStore usage.

**Files and what changes:**

| File | Current Android dep | KMP approach |
|---|---|---|
| `ServerEntities.kt` | `@Entity` annotation | Room KMP supports this in commonMain — no change |
| `Daos.kt` | `@Dao` annotation | Room KMP supports this in commonMain — no change |
| `OpenTuneDatabase.kt` | `Context` in `companion object` | Extract builder to `expect/actual`: `expect fun createDatabase(): OpenTuneDatabase` |
| `RoomMediaStateStore.kt` | None (uses DAOs) | Already clean — Room KMP DAOs work in commonMain |
| `MediaStateContracts.kt` | None | Already clean |
| `DataStoreAppConfigStore.kt` | `Context`, DataStore | Extract `AppConfigStore` interface in commonMain, `actual` with DataStore on Android, `NSUserDefaults`/`Settings` on iOS |
| `StorageBindings.kt` | `Context`, `java.io.File` | Extract `StorageBindings` as an interface in commonMain; Android `actual` takes `Context` to construct it; `ThumbnailDiskCache` uses `java.io.File` — needs an `expect/actual` for file path resolution |

**Concrete changes:**

1. **`OpenTuneDatabase.Companion.create(Context)` → `expect fun createDatabase(name: String = "opentune.db"): OpenTuneDatabase`**
   - `androidMain` actual: `Room.databaseBuilder(context, ...)`
   - `iosMain` actual: `Room.databaseBuilder<OpenTuneDatabase>(name = name, factory = { ... })`

2. **`DataStoreAppConfigStore` → `AppConfigStore` interface + platform actuals**
   ```kotlin
   // commonMain
   interface AppConfigStore {
       suspend fun getString(key: String): String?
       suspend fun putString(key: String, value: String)
   }
   // androidMain actual: DataStore-backed
   // iosMain actual: NSUserDefaults-backed
   ```

3. **`ThumbnailDiskCache` → `ThumbnailCache` interface + platform actuals**
   ```kotlin
   // commonMain
   interface ThumbnailCache {
       suspend fun get(key: String): String?  // returns local file path
       suspend fun put(key: String, data: ByteArray)
   }
   // androidMain actual: uses context.cacheDir + java.io.File
   // iosMain actual: uses NSCacheDirectory + NSFileManager
   ```

4. **`StorageBindings`** — stays in commonMain as a plain data class that holds the three interfaces (`UserMediaStateStore` is the DAO-backed implementation, `AppConfigStore`, `ThumbnailCache`). The `app` module constructs it via platform-specific factory:
   ```kotlin
   // commonMain
   data class StorageBindings(
       val serverDao: ServerDao,
       val appConfigStore: AppConfigStore,
       val thumbnailCache: ThumbnailCache,
   )
   // androidMain: expect fun createStorageBindings(context: Context): StorageBindings
   ```

**No module split.** The entire `:storage` module stays as one unit with `expect/actual` for the platform-specific constructors. Room annotations (`@Entity`, `@Dao`, `@Database`) remain in `commonMain` — Room KMP handles them.

---

## Step 7: Rename modules and update Gradle

**New `settings.gradle.kts`:**

```kotlin
rootProject.name = "OpenTune"

// Shared KMP modules (pure Kotlin — zero Android imports)
include(":provider-api")
include(":emby")
include(":device-profile")
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
| `device-profile` | `android.library` | `kotlin("jvm")` |
| `storage` (was `storage`) | `android.library` | `kotlin("jvm")` + Room KMP plugin |
| `app` | `android.application` | stays |
| `player` | `android.library` | stays |
| `smb` (was `providers:smb`) | `android.library` | stays |

**Dependency graph:**

```
app → provider-api, emby, device-profile, storage, player, smb

player → provider-api, storage
smb → provider-api
device-profile → emby (for DeviceProfile DTOs)
emby → provider-api
storage → provider-api (for MediaStateKey using providerType)
```

**Physical directory changes:**

```
Before:                          After:
app/                             app/
providers/emby/                  emby/
providers/smb/                   smb/
device-profile/                  device-profile/
storage/                         storage/  (renamed internally, no split)
player/                          player/
provider-api/                    provider-api/
```

**Migration notes:**
- `providers/emby` → `emby/` (top-level, rename)
- `providers/smb` → `smb/` (top-level, rename)
- `storage/` stays in place, no split — just updates to Room KMP
- `provider-api/` stays in place but plugin changes
- `device-profile/` stays in place but plugin changes

---

## Order of execution

1. **Step 1** — `provider-api` PlaybackContracts (highest risk, foundational)
2. **Step 2** — `emby` EmbyProviderInstance (depends on Step 1)
3. **Step 3** — PlatformContext interface in provider-api
4. **Step 4** — Audit and clean remaining emby Android imports (depends on Steps 2-3)
5. **Step 5** — device-profile split
6. **Step 6** — storage `expect/actual` decoupling
7. **Step 7** — Rename, restructure, update Gradle (depends on all above)

Steps 5 and 6 are independent of each other and can be done in parallel after Step 1.

---

## Risk and rollback

- **Step 1** changes the API that `player`, `emby`, and `smb` all depend on. Do this first, verify the app builds and plays before moving on.
- **Step 3** changes the `OpenTuneProvider` interface — all providers (emby, smb) and the registry need updating together.
- **Steps 4-5** are low risk — mostly moving files, extracting interfaces.
- **Step 6** is medium risk — `expect/actual` for Room database creation and preferences needs testing on both platforms. Room KMP is relatively new (2.6+), so verify the KSP/annotation processor works correctly.
- **Step 7** is mechanical — renames and Gradle changes. Should be a single commit after everything else is verified.
- No data migration is needed. Room schema doesn't change.
- Each step can be committed independently within the ordering constraints.
