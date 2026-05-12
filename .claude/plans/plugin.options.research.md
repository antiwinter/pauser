# Research: Platform-Independent Pluggable Providers

## Context

OpenTune currently uses a compile-time ServiceLoader pattern for providers (Emby, SMB). The goal is true runtime plugins that work across **Android + iOS + macOS** with a single deliverable — no per-platform compilation for plugin developers.

## The Core Constraint

Your `OpenTuneProvider` interface has:
- **Coroutines** (`suspend fun`) — async browse, search, playback resolution
- **Data classes** (`MediaListItem`, `PlaybackSpec`, `ServerFieldSpec`)
- **Sealed classes** (`ValidationResult`, `MediaArt`, `MediaEntryKind`)
- **Streaming** (`ItemStream` with random-access `readAt`)
- **Platform injection** (`PlatformContext`, `CodecCapabilities`)

Any plugin system must map these to something that works across all three platforms.

---

## Option A: JavaScript Plugin (QuickJS embedded)

**Deliverable:** Single `.js` file per plugin. Same file runs on Android/iOS/macOS.

### How It Works

```
┌──────────────────────────────────────────────────┐
│  OpenTune App (Kotlin/Swift)                     │
│  ┌────────────────────────────────────────────┐  │
│  │  Embedded JS Engine (QuickJS / quickjs-ng) │  │
│  │  ┌──────────────────────────────────────┐  │  │
│  │  │  telegram-provider.js                 │  │  │
│  │  │  alipan-provider.js                   │  │  │
│  │  └──────────────────────────────────────┘  │  │
│  │  Host-provided APIs:                        │  │
│  │    host.http.get(url, headers)             │  │
│  │    host.storage.get(key)                    │  │
│  │    host.cacheDir                            │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
```

The host app embeds QuickJS and exposes a host API surface:
```javascript
// Plugin developer writes:
export const provider = {
  type: "telegram",
  fields: [
    { name: "bot_token", kind: "password", label: "Bot Token" }
  ],
  async validateFields(values) {
    const resp = await host.http.get(`https://api.telegram.org/bot${values.bot_token}/getMe`);
    if (!resp.ok) throw new Error("Invalid token");
    return { hash: sha256(values.bot_token), displayName: resp.json.result.first_name };
  },
  async loadBrowsePage(instance, location, startIndex, limit) {
    const resp = await host.http.get(
      `https://api.telegram.org/bot${instance.bot_token}/getUpdates`
    );
    return parseMessagesToMediaList(resp.json(), startIndex, limit);
  },
  async resolvePlayback(instance, itemRef) {
    return {
      urlSpec: { url: `https://api.telegram.org/file/bot${instance.bot_token}/${itemRef.file_path}` },
      hooks: { progressIntervalMs: 0 }
    };
  }
};
```

### Engine Choice

| | QuickJS | quickjs-ng | JavaScriptCore | Hermes |
|---|---|---|---|---|
| Size | ~200KB | ~300KB | ~2MB (iOS built-in) | ~1.5MB |
| Memory | ~1MB per context | ~1MB | ~3MB | ~2MB |
| ES support | ES2023 | ES2024 | ES2022 | ES2021 |
| Async/Promise | Yes | Yes | Yes | Yes |
| Android | JNI bindings | JNI bindings | Not available | RN only |
| iOS | Static lib | Static lib | **Built into iOS** | Not available |
| macOS | Static lib | Static lib | **Built into macOS** | Not available |
| Maintenance | Upstream (Bellard) | Active fork | Apple maintained | Meta maintained |
| Sandboxing | Per-context isolation | Per-context isolation | Per-context | Limited |

**Recommended: quickjs-ng** — it's the actively maintained fork with ES2024 support, better WASM compilation, and active community. On iOS/macOS you could also use the built-in JavaScriptCore (zero additional binary size).

### Cross-Platform FFI Bridge

The Kotlin/Swift host needs to call JS and JS needs to call host APIs. The bridge is:

```
Kotlin/Swift host ↔ JNI/C API ↔ QuickJS runtime ↔ Plugin .js
```

- **Host → Plugin:** Call `provider.validateFields(values)`, `provider.loadBrowsePage(...)`, etc. The host serializes Kotlin/Swift objects to JS objects via the QuickJS API.
- **Plugin → Host:** `host.http.get()` is a JS function that the host registers. When called, it invokes Kotlin/Swift networking code (OkHttp/URLSession) and returns a Promise.

### Pros
- **Single deliverable** — one `.js` file, works everywhere
- **No compilation for plugin devs** — they write JS, ship a `.js` file
- **Massive ecosystem** — any JS HTTP library works (axios, fetch polyfill)
- **Familiar to most developers**
- **Hot-reloadable** — just re-evaluate the `.js` file
- **Sandboxed** — each plugin runs in its own QuickJS context
- **Small binary footprint** — ~300KB for quickjs-ng

### Cons
- **No native performance** — acceptable for HTTP-based providers, not for heavy crypto/compression
- **Plugin can't use native Android/iOS features** — only what the host exposes (HTTP, storage, cache). This is actually a feature for portability.
- **Debugging** — plugin developers can't attach a debugger easily (though you could expose a console.log → host logging bridge)
- **Type safety** — JavaScript is dynamically typed; you'd need JSDoc or a TypeScript compiler step for the SDK
- **Coroutines are async/await, not Kotlin coroutines** — the async model works but is different

### What to Provide Plugin Developers
1. **TypeScript type definitions** (`@types/opentune-provider`) — full type-safe SDK
2. **NPM package** `@opentune/provider-sdk` — contains the types + a test runner
3. **CLI tool** to validate and package a plugin (`opentune validate plugin.js`)
4. **Example plugins** — a minimal HTTP provider, one with pagination, one with playback

---

## Option B: WebAssembly (WASI) Plugin

**Deliverable:** Single `.wasm` file per plugin. Same file runs on Android/iOS/macOS.

### How It Works

```
┌──────────────────────────────────────────────────────┐
│  OpenTune App (Kotlin/Swift)                         │
│  ┌────────────────────────────────────────────────┐  │
│  │  WASM Runtime (wasm3 / WAMR)                   │  │
│  │  ┌──────────────────────────────────────────┐  │  │
│  │  │  telegram-provider.wasm                   │  │  │
│  │  │  (compiled from Rust/Go/Zig/C++)          │  │  │
│  │  └──────────────────────────────────────────┘  │  │
│  │  Host-provided WASI imports:                   │  │
│  │    wasi_http_request(method, url, headers, body)│ │
│  │    wasi_get_cache_dir() → path                 │  │
│  │    wasi_log(message)                           │  │
│  └────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

Plugin developer writes in Rust:
```rust
// Plugin developer writes:
#[opentune::provider(type = "telegram")]
struct TelegramProvider {
    bot_token: String,
}

impl Provider for TelegramProvider {
    async fn validate_fields(&self, values: &Fields) -> Result<Validation> {
        let resp = host::http::get(&format!(
            "https://api.telegram.org/bot{}/getMe", values["bot_token"]
        )).await?;
        Ok(Validation { hash: sha256(&values["bot_token"]), name: resp.name })
    }

    async fn load_browse(&self, location: Option<&str>, start: usize, limit: usize) -> BrowsePage {
        // ...
    }
}
```

### Runtime Choice

| | wasm3 | WAMR (Intel) | wasmtime | WasmEdge |
|---|---|---|---|---|
| Size | ~50KB | ~500KB | ~10MB | ~5MB |
| Mobile support | Yes (C lib) | Yes (official Android/iOS) | No (JVM only) | Yes |
| WASI support | Partial | Good | Full | Full |
| AOT compilation | No | Yes (WAMR-AOT) | N/A | Yes |
| Host function binding | C API | C API | Rust API | C/Rust API |

**Recommended: wasm3** for minimal footprint, or **WAMR** if you need AOT compilation for performance.

### Pros
- **Single deliverable** — one `.wasm` file
- **Near-native performance** — much faster than JavaScript
- **Strong sandboxing** — WASM memory is isolated, no arbitrary filesystem access
- **Language choice** — plugins can be written in Rust, Go, Zig, C++, TinyGo
- **Deterministic** — same WASM binary produces same behavior everywhere
- **Smallest runtime** — wasm3 is ~50KB

### Cons
- **Compilation step required** — plugin devs need Rust/WASM toolchain
- **No standard WASI networking** — WASI-HTTP is not finalized; you must provide custom host imports
- **String/complex data marshaling** — passing `MediaListItem` trees across the WASM boundary requires serialization (JSON or a custom format)
- **Async/coroutines** — WASM doesn't have native async yet; you need to use component model or callback-based patterns
- **Ecosystem friction** — fewer developers know Rust/WASM than JavaScript
- **Debugging** — WASM debugging is immature on mobile

---

## Option C: Local HTTP Server Plugin (Process-per-Plugin)

**Deliverable:** One executable per platform (but same source code).

### How It Works

```
┌─────────────────────────┐     ┌──────────────────────────┐
│  OpenTune App            │     │  telegram-provider        │
│                          │     │  (standalone process)     │
│  HTTP Client ────────┐   │     │  ┌──────────────────────┐ │
│  │                   │   │◄───►│  │  HTTP Server          │ │
│  └───────────────────┘   │     │  │  :port from env       │ │
│                          │     │  │                      │ │
│  Plugin Discovery:       │     │  │  Rust/Go/Node/Python  │ │
│  - Scan for executables  │     │  │                      │ │
│  - Or pre-installed svcs │     │  └──────────────────────┘ │
│                          │     └──────────────────────────┘
└─────────────────────────┘
```

The plugin is a standalone process that:
1. Starts an HTTP server on a port specified by the host (via env var or CLI arg)
2. Exposes a REST API matching your provider contract
3. The host communicates via HTTP to `localhost:PORT/browse`, `localhost:PORT/playback`, etc.

### Discovery

- **Android/iOS/macOS:** Host scans a `plugins/` directory for executables matching `opentune-provider-*`
- **Startup:** Host launches the executable, reads its stdout for the port, then communicates via HTTP

### Pros
- **Any language** — Rust, Go, Python, Node.js, anything that can run an HTTP server
- **Clean isolation** — each plugin is a separate process, crash can't bring down the host
- **Mature ecosystem** — any HTTP library, TLS client, auth library works
- **Protocol is platform-independent** — OpenAPI spec defines the contract
- **Can run remotely** — plugin doesn't even need to be on the same device (cloud provider)

### Cons
- **Not a single deliverable** — you need a compiled binary per platform+architecture
- **Startup overhead** — launching a process + HTTP handshake on each use
- **Memory overhead** — each plugin process consumes RAM
- **Distribution complexity** — how do plugin devs ship binaries for android-arm64, ios-arm64, darwin-arm64, linux-x86_64?
- **No standard way to run on mobile** — iOS especially restricts spawning child processes and JIT compilation

---

## Option D: KMP/Rust with C-ABI (Platform-Dependent Binaries)

**Deliverable:** Source code is shared (KMP/Rust), but deliverables are per-platform `.so`/`.dylib`.

### How It Works

Plugin developer writes in Kotlin (KMP) or Rust. The build produces:
- `libprovider-android-arm64.so`
- `libprovider-ios-arm64.dylib`
- `libprovider-macos-arm64.dylib`

These are loaded via FFI (JNI on Android, `dlopen` on iOS/macOS).

### The C-ABI Problem

Your current `OpenTuneProvider` interface **does not survive a C boundary**. You'd need to redefine it:

```c
// C ABI plugin interface — everything is callback-based
typedef struct {
    const char* (*get_provider_type)(void);
    const char* (*get_fields_json)(void);  // JSON string
    void (*validate_fields)(
        const char* fields_json,
        void* on_success_callback,  // callback(hash, display_name, fields_json)
        void* on_error_callback     // callback(error_message)
    );
    void (*load_browse_page)(
        void* instance,
        const char* location,
        int start_index,
        int limit,
        void* on_result_callback  // callback(json_result)
    );
    void (*resolve_playback)(
        void* instance,
        const char* item_ref,
        void* on_result_callback  // callback(json_result)
    );
    // ... every method needs to be a callback
    void* (*create_instance)(const char* fields_json, const char* codec_capabilities_json);
    void (*dispose_instance)(void* instance);
} OpenTuneProviderC;
```

All data is JSON-serialized. Async is callback-based. No type safety at the boundary.

### Pros
- **Native performance**
- **Plugin devs use full language** — Kotlin or Rust with full standard library
- **Can use native HTTP, crypto, compression**

### Cons
- **Not a single deliverable** — need platform-specific binaries
- **C ABI is painful** — the interface above is what plugin devs must implement manually
- **JSON serialization for everything** — performance hit and no compile-time type checking
- **Manual memory management** at the boundary (who frees the JSON strings?)
- **Build complexity** for plugin developers — they need NDK for Android, Xcode for iOS

---

## Comparison Matrix

| | JavaScript (QuickJS) | WebAssembly | Local HTTP Server | KMP/Rust C-ABI |
|---|---|---|---|---|
| **Single deliverable** | Yes (.js) | Yes (.wasm) | No (per-platform binary) | No (per-platform .so) |
| **Compilation needed** | No | Yes (Rust/WASM toolchain) | Yes (per platform) | Yes (NDK + Xcode) |
| **Developer familiarity** | High | Medium | Medium | Medium |
| **Performance** | Good | Excellent | Good (IPC overhead) | Excellent |
| **Async support** | async/await (native) | Callback/workaround | HTTP (natural) | Callback (manual) |
| **Type safety** | TypeScript (compile-time) | Rust/Zig (strong) | OpenAPI (schema) | None at C boundary |
| **Sandboxing** | Per-context | Memory-isolated | Process-isolated | None (same process) |
| **Ecosystem** | Huge (npm) | Growing (crates.io) | Any language | Full stdlib |
| **Binary size overhead** | ~300KB (quickjs-ng) | ~50-500KB (wasm3/WAMR) | None (separate process) | None (dlopen) |
| **iOS compatible** | Yes | Yes | Limited (no child process) | Yes |
| **Android compatible** | Yes | Yes | Yes | Yes |
| **Hot-reload** | Yes | Yes | Yes (restart process) | No |
| **Debugging** | Poor | Immature | Standard tools | Standard tools |

---

## Recommendation

### For your use case (Telegram, AliPan, WebDAV, SMB-like providers):

**JavaScript (QuickJS) is the best option.** Here's why:

1. **Providers are HTTP clients.** They call APIs, parse JSON, return structured data. JavaScript is the lingua franca of HTTP APIs.
2. **No compilation barrier.** A plugin developer can write a `.js` file and ship it immediately. No Rust toolchain, no NDK, no cross-compilation.
3. **async/await maps naturally to coroutines.** `async function loadBrowsePage()` in JS ↔ `suspend fun loadBrowsePage()` in Kotlin. The mental model is identical.
4. **Massive ecosystem.** Telegram? `axios` or `fetch`. AliPan? Same. Any HTTP auth, OAuth, multipart upload — all one `npm install` away.
5. **Sandboxed by default.** QuickJS contexts are isolated — a malicious plugin can't access filesystem or network except through your host APIs.
6. **One deliverable.** A `.js` file works on Android, iOS, macOS. Done.

### What you'd need to build (the SDK):

```
┌─ SDK for plugin developers ──────────────────────────┐
│                                                       │
│  1. TypeScript types (@types/opentune-provider)       │
│     - OpenTuneProvider interface                      │
│     - Host API types (http, storage, log)             │
│     - Result types (MediaListItem, PlaybackSpec, etc) │
│                                                       │
│  2. Runtime host (embedded in OpenTune app)           │
│     - QuickJS engine wrapper (Kotlin JNI + Swift C)   │
│     - Host API implementations (OkHttp/URLSession)    │
│     - Plugin lifecycle (load → validate → browse)     │
│     - Plugin discovery (scan plugins/ directory)      │
│                                                       │
│  3. CLI tool (npm package)                            │
│     - `opentune validate plugin.js` — check contract  │
│     - `opentune test plugin.js` — run against mock    │
│     - `opentune package plugin.js` → .opk bundle      │
│                                                       │
│  4. Example plugins                                   │
│     - minimal-provider.js (static file list)          │
│     - http-provider.js (real API calls)               │
│     - stream-provider.js (with ItemStream impl)       │
│                                                       │
└───────────────────────────────────────────────────────┘
```

### The Host API surface (what you expose to plugins):

```typescript
interface HostAPI {
  // HTTP — the most important one
  http: {
    get(url: string, headers?: Record<string, string>): Promise<HttpResponse>;
    post(url: string, body: any, headers?: Record<string, string>): Promise<HttpResponse>;
    // ... put, delete, etc.
  };

  // Storage — persistent per-plugin key-value
  storage: {
    get(key: string): Promise<string | null>;
    set(key: string, value: string): Promise<void>;
    delete(key: string): Promise<void>;
  };

  // Configuration
  config: {
    cacheDir: string;
    deviceId: string;
    clientVersion: string;
    codecCapabilities: CodecCapabilities;
  };

  // Logging
  log: {
    info(msg: string): void;
    warn(msg: string): void;
    error(msg: string): void;
  };

  // Utility
  crypto: {
    sha256(data: string): string;
  };
}
```

### Architecture Changes to OpenTune

The current `OpenTuneProviderRegistry` would have two discovery paths:
1. **ServiceLoader** (existing) — for compiled-in providers (Emby, SMB)
2. **JS Engine** (new) — scan `plugins/` directory, load each `.js` file into a QuickJS context, wrap it in a `JsBackedProvider : OpenTuneProvider` that forwards all calls to the JS runtime

The `JsBackedProvider` class is the adapter: it implements `OpenTuneProvider` in Kotlin and delegates every method call to the QuickJS context. This means **zero changes to the rest of the app** — BrowseRoute, DetailRoute, PlayerRoute all work unchanged.

### Files to Modify/Create

**Host runtime:**
- New: `provider-js/` module — QuickJS engine wrapper (JNI for Android, static lib for iOS/macOS)
- New: `JsBackedProvider` — Kotlin adapter that forwards `OpenTuneProvider` calls to JS
- Modify: [OpenTuneProviderRegistry.kt](app/src/main/java/com/opentune/app/providers/OpenTuneProviderRegistry.kt) — add JS plugin discovery
- Modify: [OpenTuneApplication.kt](app/src/main/java/com/opentune/app/OpenTuneApplication.kt) — initialize JS engine, scan plugin directory

**SDK for developers:**
- New: `sdk/typescript/` — TypeScript type definitions + test runner
- New: `sdk/cli/` — npm CLI tool for validate/package
- New: `sdk/examples/` — example plugin files

**No changes needed to:** `provider-api` interfaces, `ProviderInstanceRegistry`, any route/composable, player module.

---

## Why Not the Others

- **WebAssembly** — technically elegant but the async/coroutine story is immature. WASI-HTTP isn't standardized. The ecosystem is smaller. Plugin devs need Rust/WASM toolchain — a real barrier for "someone who wants to write a Telegram provider."

- **Local HTTP Server** — iOS doesn't allow spawning child processes (App Sandbox). The startup latency is significant. Distribution of per-platform binaries is harder than shipping a `.js` file.

- **KMP/Rust C-ABI** — the C interface is fundamentally hostile to the current API shape. Every `suspend fun` becomes a callback. Every data class becomes JSON. You'd essentially be rebuilding the same contract in a less type-safe form. Better to just use JavaScript where the async model and data serialization are natural.

## Verification

1. Build a minimal QuickJS host in Kotlin (JNI bridge)
2. Write a `JsBackedProvider` that wraps a simple JS plugin
3. Write `hello-provider.js` that returns a static list of 3 items
4. Place it in the app's plugin directory
5. Launch the app, verify it appears alongside Emby/SMB
6. Test browse → detail → playback flow
7. Repeat the same plugin on iOS build (macOS for testing)
8. Verify the same `.js` file works without modification
