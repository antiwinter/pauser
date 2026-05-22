# OpenTune architecture conventions

When adding or changing TV screens for a **content source**, follow these rules so navigation and code stay easy to grep.

## Draft development (no migrations, no legacy shims)

OpenTune is still **draft / pre-release**. Do **not**:

- Add **backward-compatibility** layers for removed code paths (no dual APIs, no "deprecated but still works" bridges, no version switches to keep old behavior alive).
- Implement **data or navigation migrations** for end users (no Room destructive-migration workarounds solely to preserve old installs).

When something changes, **update call sites and schema directly** and delete the old approach.

---

## Embedded HTTP server (`OpenTuneServer`)

[`OpenTuneServer`](app/src/main/java/com/opentune/app/server/OpenTuneServer.kt) is started in `OpenTuneApplication.onCreate()` and runs for the app's lifetime. It is the **single mechanism** through which any provider byte resource (SMB video, SMB cover, SMB sidecar subtitle) becomes a plain `http://` URL.

- Binds to `0.0.0.0` (all interfaces) on an ephemeral port. Both the local player and LAN clients can reach it.
- Implements [`StreamRegistrar`](contracts/src/main/java/com/opentune/provider/StreamRegistrar.kt) and registers itself with `StreamRegistrarHolder`.
- Token registry: `ConcurrentHashMap<token, (ProviderInstance, itemRef)>`. Each token is a random UUID hex string embedded in the URL path `/stream/<token>`.
- Route `GET /stream/{token}`: looks up token → calls `instance.openStream(itemRef)` → streams bytes, honoring `Range` headers with `206 Partial Content`.
- **Auth by token entropy**: tokens are single-use opaque strings revoked explicitly by the provider.
- All SMB URLs produced for playback and cover extraction are `http://127.0.0.1:<port>/stream/<token>` — loopback only. LAN features (future) will use the device's LAN IP.

### `StreamRegistrar` / `StreamRegistrarHolder`

Defined in [`:contracts`](contracts/src/main/java/com/opentune/provider/StreamRegistrar.kt). Providers call:

```kotlin
val url = StreamRegistrarHolder.get().registerStream(this, itemRef)   // returns http://127.0.0.1:port/stream/{token}
StreamRegistrarHolder.get().revokeToken(url)   // call when done
```

`StreamRegistrarHolder.set(openTuneServer)` is called in `OpenTuneApplication.onCreate()`.

### `ProviderStream`

Defined in [`:contracts`](contracts/src/main/java/com/opentune/provider/ProviderContracts.kt). Random-access stream with explicit `close()`. Used **only** by `OpenTuneServer`'s route handler; no player or UI code calls it directly.

```kotlin
interface ProviderStream {
    suspend fun getSize(): Long
    suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int
    fun close()
}
```

`OpenTuneProviderInstance.openStream(itemRef): ProviderStream? = null` — overridden by SMB, default null for Emby/JS.

---

## Providers

### Contracts (`:contracts`)

[`:contracts`](contracts/src/main/java/com/opentune/provider/) contains:

- **`OpenTuneProvider`** — stateless factory. Key members:
  - `val protocol: String` — stable registry key
  - `val providesCover: Boolean` — `true` if catalog list items carry HTTP cover art directly (e.g. Emby); `false` if covers must be extracted from the media stream (e.g. SMB)
  - `fun getFieldsSpec(): List<ServerFieldSpec>`
  - `suspend fun validateFields(values: Map<String, String>): ValidationResult`
  - `fun createInstance(values: Map<String, String>, capabilities: PlatformCapabilities): OpenTuneProviderInstance`
- **`OpenTuneProviderInstance`** — live protocol handle for one configured server. Key members:
  - `suspend fun listEntry(…): EntryList`
  - `suspend fun search(…): List<EntryInfo>`
  - `suspend fun getDetail(itemRef: String): EntryDetail`
  - `suspend fun getPlaybackSpec(itemRef: String, startMs: Long): PlaybackSpec`
  - `suspend fun openStream(itemRef: String): ProviderStream? = null` — opens a random-access stream; called by `OpenTuneServer` per HTTP request. Caller closes the stream. Returns `null` by default.
- **`ProviderStream`** — random-access stream with explicit `close()`. See above.
- **`StreamRegistrar`** / **`StreamRegistrarHolder`** — cross-module service locator for token registration. See above.
- **`PlaybackSpec`** — `url: String` is always non-null (SMB uses a loopback URL from `OpenTuneServer`). No `customMediaSourceFactory`.
- **`OpenTunePlaybackHooks`**, **`ServerFieldSpec`**, **`ValidationResult`**, **`SubmitResult`**.

### Registry

[`OpenTuneProviderRegistry`](app/src/main/java/com/opentune/app/providers/OpenTuneProviderRegistry.kt) on [`OpenTuneApplication`](app/src/main/java/com/opentune/app/OpenTuneApplication.kt) maps `protocol` string → `OpenTuneProvider` instance. Register new backends there only.

### Implementations

| Module | Provider | `providesCover` | `openStream` |
|---|---|---|---|
| `:providers:emby` | `EmbyProvider` / `EmbyProviderInstance` | `true` | not overridden (null) |
| `:providers:smb` | `SmbProvider` / `SmbProviderInstance` | `false` | overridden — opens smbj `DiskShare`, wraps in `SmbProviderStream : ProviderStream` |

**Source-prefixed identifiers** (`Emby*`, `Smb*`) are confined to their respective modules. Do **not** place them under `ui/catalog`, `ui/home`, or `app/.../providers/` (only the neutral registry and [`ServerConfigRepository`](app/src/main/java/com/opentune/app/providers/ServerConfigRepository.kt) reside in `:app`).

Providers **never** import `:storage`.

---

## Storage (`:storage`)

[`:storage`](storage/src/main/java/com/opentune/storage/) owns all persistence. Key types:

- **`ServerEntity`** — `@PrimaryKey val sourceId: String` (`"${providerType}_${hash}"`), `providerType`, `displayName`, `fieldsJson`, timestamps.
- **`MediaStateEntity`** — composite PK `(sourceId, itemId)`. Field `providerType` is stored but is not part of the PK. Notable cached-asset fields:
  - `cachedCover: String?` — tri-state: `null` = not yet attempted; `MediaStateEntity.CACHE_FAILED = "failed"` = extraction failed / never retry; any other string = SHA-256 blob key into `OssCache`.
  - `cachedBackdrops: String?` — `null` = not tried; `""` = failed / none; otherwise space-separated SHA-256 blob keys.
  - `cachedLogo: String?` — same tri-state as `cachedCover`.
- **`OssCacheEntity`** — `@PrimaryKey val key: String` (SHA-256 hex of blob bytes), `sizeBytes`, `lastVisit`, `pinned`. Tracks LRU metadata for `OssCache`.
- **`UserMediaStateStore`** / **`RoomMediaStateStore`** — CRUD for `MediaStateEntity`. Method `upsertCachedAssets(protocol, sourceId, itemId, cover, backdrops, logo)` persists all asset keys at once.
- **`OssCache`** — content-addressed blob cache at `cacheDir/oss/<sha256key>`. Keys are SHA-256 hex of the stored bytes; identical blobs share one file. LRU eviction driven by `OssCacheDao` — no explicit per-source deletion. API: `put(bytes): String`, `getPath(key): String?`, `pin(key)`, `unpin(key)`. If a file is evicted by LRU, `getPath` returns `null` and `AssetGenerator` re-extracts transparently.
- **`OpenTuneStorageBindings`** — exposes `serverDao`, `mediaStateStore`, `appConfigStore`, `ossCache`. Created by `OpenTuneApplication` and passed to routes via `app.storageBindings`.

**Database version: 9.** Uses `fallbackToDestructiveMigration`.

---

## Cover art

### List covers (`providesCover = false` providers, e.g. SMB)

Cover generation is handled by [`rememberAssetGenerator`](app/src/main/java/com/opentune/app/ui/catalog/AssetGenerator.kt), a `@Composable` hook used in `BrowseRoute` and `SearchRoute`:

```kotlin
val assetGenerator = rememberAssetGenerator(app, providerType, sourceId, instance, items)
```

`items` is a `SnapshotStateList<EntryInfo>` owned by the route. When a cover is resolved, `rememberAssetGenerator` writes it directly into the list (`items[idx] = items[idx].copy(cover = path)`), which drives recomposition automatically. **No parallel override map; no extra cover props on `MediaEntryComponent`.**

Priority chain per item:
1. `mediaStateStore.get(…)?.cachedCover` — fast DB lookup for the blob key
2. `ossCache.getPath(key)` — resolve key to file path; if the file was LRU-evicted, `getPath` returns `null` and falls through to re-extraction automatically
3. `instance.getPlaybackSpec(itemId, 0)` → `PlaybackSpec` → `MediaMetadataRetriever.setDataSource(spec.url, spec.headers)` → `.embeddedPicture` → `spec.hooks.onDispose()` in `finally`. The same contract the player uses: SMB resolves a loopback URL and `onDispose()` revokes its stream tokens; any future HTTP provider with embedded art works identically at no extra code cost. Extracted bytes are stored via `ossCache.put(bytes)` which returns the SHA-256 content key.
4. On failure: write `CACHE_FAILED` sentinel to DB, never retried

Extraction is bounded to **4 concurrent jobs** via `Semaphore(4)`. Items with `CACHE_FAILED` or an already-resolved cover are skipped immediately.

When `provider.providesCover = true` (Emby), `rememberAssetGenerator` returns a no-op and does no work.

### Detail poster

`DetailScreen` renders `detail.poster` (not `detail.cover`). `MediaArt.None` renders nothing. Posters are not cached on disk.

### Cover clean-up on provider removal / identity change

[`ServerConfigRepository`](app/src/main/java/com/opentune/app/providers/ServerConfigRepository.kt) `removeServer` and the identity-change edit branch both execute, in order:
1. `mediaStateStore.deleteBySource(sourceId)` — deletes all Room rows for the source (removes cached key references)
2. `serverDao.deleteBySourceId(sourceId)` — deletes the server record
3. `instanceRegistry.remove(sourceId)` — evicts the live instance

No explicit file deletion is needed — `OssCache` LRU eviction handles cleanup automatically.

---

## Shared catalog UI

Routes and screens under **`app/.../ui/catalog`**:

| File | Role |
|---|---|
| `BrowseRoute` / `SearchRoute` | Create `mutableStateListOf<EntryInfo>()`, call `rememberAssetGenerator`, pass both to the screen |
| `BrowseScreen` / `SearchScreen` | Accept `SnapshotStateList<EntryInfo>`; populate with `.clear()` + `.addAll()`; call `onItemsLoaded` after each batch |
| `MediaEntryComponent` | Renders `item.cover` directly — no cover override param |
| `DetailRoute` / `DetailScreen` | Load `EntryDetail`, render `detail.poster` |
| `AssetGenerator` | `rememberAssetGenerator` hook + `updateItemCover` helper; currently covers only |

**Player shell:** [`OpenTunePlayerScreen`](player/src/main/java/com/opentune/player/OpenTunePlayerScreen.kt) in `:player` takes `PlaybackSpec` only — no SMB/Emby branching.

---

## Navigation route strings

Unified catalog flows (`providerType` values come from `OpenTuneProvider.protocol`):

- `browse/{providerType}/{sourceId}/{location}` — URL-encoded `location` (opaque to Nav)
- `detail/{providerType}/{sourceId}/{itemRef}`
- `player/{providerType}/{sourceId}/{itemRef}/{startMs}`
- `search/{providerType}/{sourceId}/{scopeLocation}`

Server configuration (neutral):

- `provider_add/{providerType}` — `Routes.providerAdd`
- `provider_edit/{providerType}/{sourceId}` — `Routes.providerEdit`

Encode/decode in `Routes` and/or `CatalogNav` only — avoid scattering magic strings. Libraries root token: `CatalogNav.LIBRARIES_ROOT_SEGMENT` = `CatalogRouteTokens.LIBRARIES_ROOT_SEGMENT`.

---

## Server config UI

[`ServerAddRoute`](app/src/main/java/com/opentune/app/ui/config/ServerAddRoute.kt) / [`ServerEditRoute`](app/src/main/java/com/opentune/app/ui/config/ServerEditRoute.kt) under `ui/config`. Driven by `provider.getFieldsSpec()`. Field labels resolve via `strings.xml` + [`ProviderFieldLabels`](app/src/main/java/com/opentune/app/ui/config/ProviderFieldLabels.kt).

---

## Log tags

- Cover/asset generation: `"OT_AssetGenerator"`.
- Embedded server: `"OpenTuneServer"`.
- Player: `"OpenTunePlayer"` (from `OPEN_TUNE_PLAYER_LOG`); add provider hints in log *messages* if needed.

---

## Playback hooks

Implement `OpenTunePlaybackHooks` from `:contracts`. HTTP-library: `EmbyPlaybackHooks` in `:providers:emby`. File-share: `SmbPlaybackHooks` in `:providers:smb` (revokes stream tokens on dispose).

---

## Cursor Cloud specific instructions

### Environment prerequisites

| Component | Path / Version |
|-----------|---------------|
| JDK 17 | `/usr/lib/jvm/java-17-openjdk-amd64` |
| Android SDK | `/opt/android-sdk` (platforms 35, build-tools 35, NDK 30.0.14904198, CMake 3.22.1) |
| Gradle wrapper | `./gradlew` (downloads Gradle 9.4.1 on first run) |
| QuickJS submodule | `providers/js/src/main/jni/quickjs_ng` — must be initialized |

Environment variables (`JAVA_HOME`, `ANDROID_SDK_ROOT`, `PATH`) are set in `~/.bashrc`.

### Key commands

| Task | Command |
|------|---------|
| Build debug APK | `./gradlew assembleDebug` |
| Run unit tests | `./gradlew testDebugUnitTest` |
| Lint (all modules) | `./gradlew lint` |
| Lint (app only) | `./gradlew :app:lintDebug` |

### Caveats

- `local.properties` must exist with `sdk.dir=/opt/android-sdk`. It is gitignored; the update script recreates it.
- `providers-ts/dist/` is referenced as an assets source dir by `:app`. If missing, create an empty directory — the build won't fail but the assets merge step needs the path to exist.
- Pre-existing lint errors: `:gen-art` has a `NewApi` error (`MediaMetadataRetriever.use` on API < 29) and `:app` has an `UnsafeOptInUsageError` for Media3 unstable API usage. These are not regressions.
- The project uses `--no-daemon` by default in CI-like environments. For faster iteration in a long-lived session, omit `--no-daemon` to keep the Gradle daemon alive.
- This is an Android TV app — there is no web server or backend to start. "Running" the app means building the APK. End-to-end testing requires an Android TV emulator or device plus an Emby server or SMB share on the network.
