# Refactor Providers to Rust

## Context

OpenTune currently has its providers (Emby, SMB) implemented as JVM Kotlin modules, tightly coupled to the Android/Gradle build system. The goal is to move provider logic to Rust, compiled as dynamic libraries (`.so`/`.dylib`), with a C-ABI boundary that allows Kotlin (Android) or Swift (iOS/macOS) to call through. This makes the provider layer **platform-independent** — the same Rust code can serve all platforms.

Storage stays per-platform: Room on Android, Core Data / SQLite on iOS/macOS.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│  OpenTune App (Kotlin on Android / Swift on iOS-macOS)     │
│  ┌─────────────────┐    ┌───────────────────────────────┐   │
│  │  UI Layer        │    │  FFI Bridge (JNI on Android  │   │
│  │  Compose TV      │    │  / C-bridge on iOS-macOS)     │   │
│  │  Navigation      │    │                               │   │
│  └────────┬─────────┘    │  ┌─────────────────────────┐  │   │
│           │              │  │  libopentune_backend.so  │  │   │
│           ├──────────────┼─►│  │ Provider Registry      │  │   │
│           │              │  │  │ ┌───────────────────┐ │  │   │
│           ├──────────────┼─►│  │ │ EmbyProvider.so   │ │  │   │
│           │              │  │  │ │ SmbProvider.so    │ │  │   │
│           ├──────────────┼─►│  │ │ (future: more...) │ │  │   │
│           │              │  │  │ └───────────────────┘ │  │   │
│           │              │  │  └─────────────────────────┘  │   │
│           │              │  └───────────────────────────────┘   │
│           │              └──────────────────────────────────────┘
│           │
│  Storage: Room (Android) / Core Data (iOS/macOS) — per-platform
└───────────┼──────────────────────────────────────────────────────┘
            │
      All data crosses the C-ABI as JSON strings + primitive types
```

## Design Decisions

### C-ABI Strategy

Every public function in the Rust library is `#[no_mangle] pub extern "C"`. The interface uses:
- **JSON strings** for all complex data (data classes, sealed classes, maps, lists)
- **Primitives** for simple values (i64 for Long, i32 for Int, f64 for Float, bool for Boolean)
- **Opaque pointers** for handles (ProviderInstance, Stream)
- **Callbacks** for async (the caller passes a function pointer + context, Rust calls it when done)
- **Caller-allocated buffers** for `readAt` (the caller provides `*mut u8` and capacity)

### Memory Model

- Rust owns all heap memory. Kotlin/Swift never frees Rust-allocated pointers.
- Every `*mut c_char` returned from Rust must be freed by calling `opentune_free_string(ptr)`.
- Every `*mut ProviderInstance` is an opaque handle managed by Rust's `HashMap`.
- `ItemStream.readAt` uses caller-provided buffers — Rust writes into them, returns bytes_read.

### Async Model

Kotlin coroutines ↔ Rust async is bridged via callbacks:
```
Kotlin suspend fun → JNI calls Rust async fn → Rust spawns tokio task
→ Rust calls Kotlin callback on completion → Kotlin resumes coroutine via suspendCancellableCoroutine
```

The Kotlin side wraps each FFI call in `suspendCancellableCoroutine { cont -> ... }`, passing a callback that calls `cont.resume(result)`.

### Dynamic Library Loading

Providers are loaded with `dlopen` (Unix):
1. App scans a `providers/` directory for `libopentune_provider_*.so` / `*.dylib`
2. For each library, `dlopen` loads it, `dlsym` resolves the `opentune_provider_init` entry point
3. The init function returns an `OpenTuneProviderC` vtable struct
4. The registry stores vtables in a `HashMap<String, ProviderVTable>` keyed by `providerType`

---

## The C-ABI Surface

### Core Types (C header — `opentune_backend.h`)

```c
// --- Opaque handles ---
typedef struct opentune_provider_instance opentune_provider_instance;
typedef struct opentune_item_stream opentune_item_stream;

// --- Strings (caller must free via opentune_free_string) ---
void opentune_free_string(char* s);

// --- Error codes ---
typedef enum {
    OPENTUNE_OK = 0,
    OPENTUNE_ERR_INVALID_ARGS = 1,
    OPENTUNE_ERR_PROVIDER_NOT_FOUND = 2,
    OPENTUNE_ERR_NETWORK = 3,
    OPENTUNE_ERR_DECODE = 5,
} opentune_error_code;

// --- Provider Registry API ---

// Discover and load all provider dynamic libraries from a directory.
// Returns the number of providers loaded.
int opentune_provider_discover(const char* plugins_dir);

// Get list of discovered provider types (JSON array of strings).
int opentune_provider_list(char** out_json);

// Get the field specs for a provider type (JSON array of ServerFieldSpec).
int opentune_provider_get_fields(const char* provider_type, char** out_json);

// Validate credentials for a provider. Returns JSON ValidationResult.
int opentune_provider_validate(const char* provider_type,
    const char* fields_json, char** out_result_json);

// Bootstrap a provider (call once at startup).
int opentune_provider_bootstrap(const char* provider_type,
    const char* platform_context_json);  // {deviceName, deviceId, clientVersion, cacheDir}

// Create a provider instance. Returns an opaque handle.
int opentune_provider_create_instance(const char* provider_type,
    const char* fields_json, const char* codec_capabilities_json,
    opentune_provider_instance** out_instance);

// Destroy a provider instance.
int opentune_provider_destroy_instance(opentune_provider_instance* instance);

// --- Provider Instance API (all suspend in Kotlin → async callback in C) ---

typedef void (*browse_result_cb)(void*, int, const char*);
typedef void (*detail_result_cb)(void*, int, const char*);
typedef void (*playback_result_cb)(void*, int, const char*);
typedef void (*search_result_cb)(void*, int, const char*);

void opentune_instance_load_browse_page(
    opentune_provider_instance* instance,
    const char* location, int start_index, int limit,
    void* cb_ctx, browse_result_cb cb);

void opentune_instance_search_items(
    opentune_provider_instance* instance,
    const char* scope_location, const char* query,
    void* cb_ctx, search_result_cb cb);

void opentune_instance_load_detail(
    opentune_provider_instance* instance,
    const char* item_ref,
    void* cb_ctx, detail_result_cb cb);

void opentune_instance_resolve_playback(
    opentune_provider_instance* instance,
    const char* item_ref, int64_t start_ms,
    void* cb_ctx, playback_result_cb cb);

// --- ItemStream API ---
int opentune_stream_read_at(
    opentune_item_stream* stream,
    int64_t position, uint8_t* buffer, int buffer_size);
int64_t opentune_stream_get_size(opentune_item_stream* stream);
int opentune_stream_close(opentune_item_stream* stream);
```

### JSON Data Contracts

All complex types cross the boundary as JSON. The Kotlin side serializes/deserializes with `kotlinx.serialization`, the Rust side with `serde_json`. Both sides agree on the same JSON schema.

**ValidationResult JSON:**
```json
{"type": "success", "hash": "abc123", "displayName": "My Emby", "fieldsJson": "{...}"}
{"type": "error", "message": "Connection failed"}
```

**PlaybackSpec JSON:**
```json
{
  "urlSpec": {"url": "http://...", "headers": {"X-Token": "..."}, "mimeType": "video/mp4"},
  "customMediaSourceFactory": null,
  "displayTitle": "The Matrix",
  "durationMs": 8160000,
  "hooks": {"progressIntervalMs": 10000},
  "subtitleTracks": [{"trackId": "1", "label": "English", "language": "en", ...}],
  "subtitleHeaders": {}
}
```

**MediaListItem JSON:**
```json
{
  "id": "12345",
  "title": "The Matrix",
  "kind": "Playable",
  "cover": {"type": "http", "url": "http://.../thumb.jpg"},
  "userData": {"positionMs": 30000, "isFavorite": false, "played": true},
  "originalTitle": null,
  "genres": ["Sci-Fi", "Action"],
  "communityRating": 8.7,
  "studios": ["Warner Bros"],
  "etag": null,
  "indexNumber": null
}
```

**PlatformContext JSON:**
```json
{
  "deviceName": "NVIDIA Shield TV",
  "deviceId": "abc123",
  "clientVersion": "1.0.0",
  "cacheDir": "/data/data/com.opentune.app/cache"
}
```

**CodecCapabilities JSON:**
```json
{
  "supportedVideoMimeTypes": ["video/avc", "video/hevc"],
  "supportedAudioMimeTypes": ["audio/mp4a-latm"],
  "maxVideoPixels": 2073600,
  "supportedSubtitleFormats": ["srt", "ass", "ssa", "vtt", "webvtt"]
}
```

**BrowsePageResult JSON:**
```json
{
  "items": [/* MediaListItem array */],
  "totalCount": 42
}
```

**MediaDetailModel JSON:**
```json
{
  "title": "The Matrix",
  "overview": "A computer hacker...",
  "logo": {"type": "http", "url": "..."},
  "backdropImages": ["http://.../bg1.jpg", "http://.../bg2.jpg"],
  "canPlay": true,
  "communityRating": 8.7,
  "bitrate": 20000000,
  "externalUrls": [{"name": "IMDb", "url": "https://..."}],
  "productionYear": 1999,
  "providerIds": {"imdb": "tt0133093"},
  "mediaStreams": [{"index": 0, "type": "video", "codec": "h264", ...}],
  "etag": null
}
```

**ServerFieldSpec JSON:**
```json
{
  "id": "base_url",
  "labelKey": "server_field_base_url",
  "kind": "SingleLineText",
  "required": true,
  "sensitive": false,
  "order": 0,
  "placeholderKey": null
}
```

---

## Rust Crate Structure

```
opentune-backend/
├── Cargo.toml
├── Cargo.lock
├── src/
│   ├── lib.rs                    # #[no_mangle] C-ABI entry points
│   ├── c_types.rs                # C-compatible type definitions (opaque structs)
│   ├── error.rs                  # Result<T> → error_code mapping
│   ├── provider/
│   │   ├── mod.rs                # ProviderRegistry, discover/load vtables
│   │   ├── vtable.rs             # OpenTuneProviderC vtable struct
│   │   └── instance.rs           # InstanceManager (HashMap of instances)
│   └── models/
│       ├── mod.rs                # All data types with serde Serialize/Deserialize
│       ├── catalog.rs            # MediaListItem, BrowsePageResult, MediaDetailModel, etc.
│       ├── playback.rs           # PlaybackSpec, PlaybackUrlSpec, SubtitleTrack, etc.
│       ├── provider.rs           # ServerFieldSpec, ValidationResult, etc.
│       └── platform.rs           # PlatformContext, CodecCapabilities
│
├── providers/
│   ├── Cargo.toml                # workspace member for provider plugins
│   ├── emby/
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── lib.rs            # #[opentune_provider(type = "emby")] macro invocation
│   │       ├── api.rs            # Emby API calls (reqwest)
│   │       └── models.rs         # Emby-specific response types
│   └── smb/
│       ├── Cargo.toml
│       └── src/
│           ├── lib.rs
│           ├── session.rs        # SMB session wrapper
│           └── file.rs           # SmbItemStream implementation
│
├── macros/                       # proc-macro crate for provider registration
│   ├── Cargo.toml
│   └── src/lib.rs                # #[opentune_provider] macro → generates init fn
│
└── cbindgen.toml                 # auto-generate opentune_backend.h from Rust
```

### Provider Plugin Macro

```rust
// In providers/emby/src/lib.rs:
#[opentune::provider(type = "emby", provides_cover = true)]
struct EmbyProvider;

impl opentune::Provider for EmbyProvider {
    fn get_fields_spec() -> Vec<ServerFieldSpec> { ... }
    async fn validate_fields(values: HashMap<String, String>) -> ValidationResult { ... }
    fn create_instance(values: HashMap<String, String>, caps: CodecCapabilities) -> Box<dyn ProviderInstance> { ... }
    async fn bootstrap(ctx: PlatformContext) { ... }
}

// Macro generates:
// #[no_mangle] pub extern "C" fn opentune_provider_init() -> OpenTuneProviderC { ... }
// which returns a vtable with function pointers to the impl above.
```

### Provider Plugin Loading (dlopen flow)

```rust
// In provider/vtable.rs:
pub fn load_plugin(path: &Path) -> Result<ProviderPlugin, Error> {
    let lib = unsafe { libloading::Library::new(path)? };
    let init_fn: libloading::Symbol<unsafe extern "C" fn() -> OpenTuneProviderC> =
        unsafe { lib.get(b"opentune_provider_init")? };
    let vtable = unsafe { init_fn() };
    Ok(ProviderPlugin {
        provider_type: vtable.get_provider_type(),
        vtable,
        _lib: lib,  // keep the library loaded
    })
}
```

---

## Kotlin FFI Bridge

### JNI Wrapper (Android)

```kotlin
// In app/src/main/jni/ (CMake builds libopentune_jni.so which links to libopentune_backend.so)

object OpentuneBackend {
    init { System.loadLibrary("opentune_jni") }

    external fun providerDiscover(pluginsDir: String): Int
    external fun providerList(): String  // JSON array

    external fun providerGetFields(providerType: String): String
    external fun providerValidate(providerType: String, fieldsJson: String): String
    external fun providerBootstrap(providerType: String, platformContextJson: String)
    external fun providerCreateInstance(providerType: String, fieldsJson: String, capsJson: String): Long
    external fun providerDestroyInstance(instanceHandle: Long)

    // Async methods use callback registration
    external fun instanceLoadBrowsePage(
        instanceHandle: Long, location: String?, startIndex: Int, limit: Int,
        callback: BrowseCallback  // (error: Int, jsonResult: String?) -> Unit
    )
    // ... similar for search, detail, playback
}
```

The JNI layer is thin: it translates Kotlin types to C types and calls the `libopentune_backend.so` functions.

### Kotlin Adapter (pure Kotlin, no JNI details leaked)

```kotlin
// In a new :backend-ffi module

class RustProviderBackend(
    private val instanceHandle: Long
) : OpenTuneProviderInstance {
    override suspend fun loadBrowsePage(
        location: String?, startIndex: Int, limit: Int
    ): BrowsePageResult =
        suspendCancellableCoroutine { cont ->
            OpentuneBackend.instanceLoadBrowsePage(
                instanceHandle, location, startIndex, limit
            ) { err, json ->
                if (err != 0) cont.resumeWithException(ProviderError(err))
                else cont.resume(Json.decodeFromString(json))
            }
        }

    override suspend fun resolvePlayback(
        itemRef: String, startMs: Long
    ): PlaybackSpec =
        suspendCancellableCoroutine { cont ->
            OpentuneBackend.instanceResolvePlayback(
                instanceHandle, itemRef, startMs
            ) { err, json ->
                if (err != 0) cont.resumeWithException(ProviderError(err))
                else cont.resume(Json.decodeFromString(json))
            }
        }
    // ... searchItems, loadDetail
}

// The old ServiceLoader path can remain for a transition period.
```

---

## Platform-Specific Loading

### Android
- Rust compiles to `libopentune_backend.so` via `cargo-ndk` (arm64-v8a, armeabi-v7a, x86_64)
- Provider plugins compile to `libopentune_provider_emby.so`, etc.
- JNI library `libopentune_jni.so` links against `libopentune_backend.so`
- Libraries bundled in APK's `jniLibs/` or loaded from `nativeLibraryDir`
- Plugin directory: `<appContext>.nativeLibraryDir` + optional `plugins/` in files dir

### iOS
- Rust compiles to static library via `cargo-lipo` or `xcode-rust`
- Swift uses `@_silgen_name` to call C functions directly (no JNI needed)
- Provider plugins: statically linked at build time (iOS doesn't support `dlopen` for third-party code in App Store builds)

### macOS
- Rust compiles to `libopentune_backend.dylib`
- Swift calls via C bridge header
- Provider plugins loaded via `dlopen` from `~/Library/Application Support/OpenTune/plugins/`

---

## Migration Strategy

### Phase 1: Rust Crate + C-ABI Foundation

1. Create `opentune-backend/` Cargo workspace
2. Define all provider data types in Rust with serde (`models/`)
3. Implement the C-ABI vtable layer (`c_types.rs`, `error.rs`)
4. Implement `ProviderRegistry` with dlopen (`provider/`)
5. Implement `InstanceManager` (`provider/instance.rs`)
6. Implement `#[opentune_provider]` proc macro (`macros/`)
7. Generate C header with `cbindgen`
8. Build JNI wrapper for Android

### Phase 2: Port Emby Provider

1. Port Emby provider from Kotlin to Rust (reqwest for HTTP, same API calls)
2. Compile Emby as a dynamic library
3. Wire up `RustProviderBackend` Kotlin adapter
4. Feature flag `useRustProviders = false/true`
5. Test: Emby login, browse, playback — identical behavior to current Kotlin Emby

### Phase 3: Port SMB Provider

1. Port SMB provider to Rust (use `smb2` crate or `libsmb2`)
2. Compile as dynamic library
3. Test: SMB browse, playback, subtitle loading, cover extraction
4. The SMB provider is the hardest because it uses Media3's custom DataSource —
   on the Rust side, `resolvePlayback` returns a `urlSpec` with `url: null` and
   `needsCustomPipeline: true`, signaling the Kotlin player layer to use
   a Rust-backed stream. The `withStream` + `ItemStream` bridge is:
   - Rust creates `opentune_item_stream` handle
   - Kotlin wraps it in `ItemStreamMediaDataSource` for `MediaMetadataRetriever`
   - For playback, Rust stream → Kotlin reads via `readAt` → feeds Media3's `DataSource`

### Phase 4: Remove Old JVM Backend

1. Remove `:providers:emby` and `:providers:smb` Gradle modules
2. Remove `:provider-api` Gradle module (types now in Rust, Kotlin adapter re-exposes them)
3. Set feature flag to `true` permanently
4. Remove ServiceLoader discovery code
5. Clean up dead code

---

## What to Provide Plugin Developers (SDK)

### For Rust Plugin Authors

1. **`opentune` crate on crates.io** — the provider trait + macro + data types
   ```toml
   [dependencies]
   opentune = "0.1"
   reqwest = "0.12"  # or anyHTTP library
   ```

2. **Template repository** — `cargo generate opentune/provider-template`
   - Pre-built `Cargo.toml`, `src/lib.rs` with a skeleton provider
   - Instructions for cross-compiling: `cargo ndk`, `cargo build --target aarch64-apple-darwin`

3. **Example providers** in the main repo:
   - `providers/emby/` — full HTTP provider with authentication
   - `providers/smb/` — protocol provider with streaming

4. **Build scripts**:
   - `build-android.sh` — cross-compile for all Android ABIs
   - `build-apple.sh` — cross-compile for iOS + macOS

### For Non-Rust Plugin Authors

The C-ABI is language-agnostic. A provider can be written in C, C++, Zig, or any language that can produce a C-compatible shared library:

```c
// A C provider:
#include "opentune_backend.h"

static int my_validate(const char* fields_json, char** out_result_json) {
    // parse JSON, make HTTP call, return JSON result
}

OpenTuneProviderC opentune_provider_init() {
    return (OpenTuneProviderC) {
        .get_provider_type = my_get_type,
        .validate_fields = my_validate,
        .create_instance = my_create_instance,
        // ...
    };
}
```

This is how you eventually get a Telegram provider written in Python (compiled via Cython) or Go (compiled to .so with `c-shared`).

---

## Files to Create / Modify

### New Rust workspace
- `opentune-backend/Cargo.toml` — workspace root
- `opentune-backend/src/lib.rs` — C-ABI entry points
- `opentune-backend/src/c_types.rs` — opaque handle structs
- `opentune-backend/src/error.rs` — error code mapping
- `opentune-backend/src/provider/mod.rs` — ProviderRegistry, dlopen
- `opentune-backend/src/provider/vtable.rs` — OpenTuneProviderC vtable
- `opentune-backend/src/provider/instance.rs` — InstanceManager
- `opentune-backend/src/models/*.rs` — All data types (serde)
- `opentune-backend/providers/emby/` — Emby provider as Rust plugin
- `opentune-backend/providers/smb/` — SMB provider as Rust plugin
- `opentune-backend/macros/` — proc-macro for `#[opentune_provider]`
- `opentune-backend/cbindgen.toml` — header generation config

### New Kotlin/Android
- New Gradle module `:backend-ffi` — Kotlin adapter over JNI
- `app/src/main/jni/` — CMake build for JNI glue layer
- `app/src/main/jni/opentune_jni.c` — JNI function implementations
- `app/src/main/jni/CMakeLists.txt` — links to libopentune_backend.so

### Modified Android
- `app/build.gradle.kts` — add `:backend-ffi` dependency, externalNativeBuild for CMake
- [OpenTuneApplication.kt](app/src/main/java/com/opentune/app/OpenTuneApplication.kt) — initialize Rust backend, pass plugin dir
- [OpenTuneProviderRegistry.kt](app/src/main/java/com/opentune/app/providers/OpenTuneProviderRegistry.kt) — delegate to Rust provider discovery
- `app/src/main/java/com/opentune/app/providers/ProviderInstanceRegistry.kt` — delegate to Rust instance manager

### To Remove (Phase 4)
- `providers/emby/` — entire module
- `providers/smb/` — entire module
- `provider-api/` — entire module
- `:providers:emby`, `:providers:smb`, `:provider-api` from settings.gradle.kts

---

## Key Challenges and Mitigations

### 1. Custom MediaSource (SMB)
SMB currently returns `customMediaSourceFactory: () -> Any` which produces a `ProgressiveMediaSource.Factory { SmbDataSource(...) }`. In Rust, this can't work — Rust can't return a Kotlin `MediaSource`.

**Solution:** Rust's `resolvePlayback` for SMB returns `urlSpec = null, needsCustomPipeline = true`. The Kotlin player layer checks this flag and uses a `RustDataSource` that wraps `opentune_item_stream` via JNI. The stream reads are: Kotlin Media3 calls `RustDataSource.read()` → JNI calls `opentune_stream_read_at()` → Rust SMB reads from network → returns bytes.

### 2. Cover Extraction
Currently `withStream` returns an `ItemStream` which is bridged to `MediaMetadataRetriever` via `ItemStreamMediaDataSource`. The same pattern works in Rust — the Rust `opentune_item_stream` handle is wrapped in the same `ItemStreamMediaDataSource`.

### 3. Callback Memory Safety
When Kotlin calls an async Rust function with a callback, the callback is a JNI global reference. If the Kotlin coroutine is cancelled, the JNI reference must be deleted to prevent leaks.

**Solution:** Use `suspendCancellableCoroutine` with an `invokeOnCancellation` handler that signals Rust to cancel and cleans up the JNI global ref.

### 4. String Lifetime
Rust returns `*mut c_char` for JSON strings. If Kotlin doesn't call `opentune_free_string`, memory leaks. If Kotlin calls it twice, double-free crash.

**Solution:** Kotlin wrapper always wraps the returned string in a Kotlin `String` (copies it), then immediately calls `opentune_free_string`. The JNI wrapper handles this automatically — the Kotlin layer never sees raw pointers.

### 5. tokio Runtime
Rust async providers need a tokio runtime. Creating one per call is expensive.

**Solution:** Create a single `tokio::runtime::Runtime` at `opentune_backend_init` time (a new C-ABI entry point called once at app startup). Store it globally. All async operations are spawned on this runtime. The runtime is dropped at `opentune_backend_shutdown` (called on app teardown).

### 6. Cross-compilation Complexity
Building Rust for Android (4 ABIs) + iOS (arm64 + simulator) + macOS (arm64 + x86_64) is non-trivial.

**Solution:** Use `cargo-ndk` for Android (mature, well-documented), `cargo-lipo` or `xcode-rust` for Apple targets. Provide Makefiles / scripts in the repo. CI builds all targets.

---

## Dependency Versions (Rust)

```toml
# Cargo.toml
[dependencies]
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"          # JSON serialization
reqwest = { version = "0.12", features = ["json", "rustls-tls"] }  # HTTP for Emby
tokio = { version = "1", features = ["rt-multi-thread", "macros"] }  # async runtime
libloading = "0.8"           # dlopen for provider plugins
jni = "0.21"                 # JNI bindings for Android
cbindgen = "0.28"            # C header generation (dev dependency)
log = "0.4"                  # logging
smb2 = "0.5"                 # SMB2 client (for SMB provider)

[workspace]
members = [".", "providers/emby", "providers/smb", "macros"]
```

---

## Verification

### Phase 1 (Foundation)
1. Build `libopentune_backend.so` for Android (arm64-v8a)
2. Load it from a minimal test app via JNI
3. Call `opentune_provider_discover` with a directory containing no plugins — returns 0
4. Call `opentune_provider_list` — returns `[]`

### Phase 2 (Emby Provider)
1. Build Emby as `libopentune_provider_emby.so`
2. Place it in the plugins directory alongside the test app
3. Call `opentune_provider_discover` — returns 1
4. Call `opentune_provider_validate` with valid Emby credentials — returns `{"type":"success",...}`
5. Create instance, call `loadBrowsePage` — returns valid MediaListItem JSON
6. Integrate with real app via feature flag — browse Emby library, play video, verify playback hooks fire

### Phase 3 (SMB Provider)
1. Build SMB as `libopentune_provider_smb.so`
2. Add SMB server via Rust backend
3. Browse shares — verify identical directory listing to current Kotlin SMB
4. Play video — verify playback works via RustDataSource
5. Verify subtitles load (sidecar file scan + download)
6. Verify cover extraction works (withStream → MediaMetadataRetriever)

### Phase 4 (Cleanup)
1. Remove old modules, verify app builds and runs
2. Run full test suite (if any exist)
3. Test on Android TV device
4. Test on macOS build (if applicable)
5. Verify no memory leaks (Android profiler during extended use)
