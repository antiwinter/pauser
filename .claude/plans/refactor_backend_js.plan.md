# Port Emby Provider to JavaScript Runtime

## Context

OpenTune currently has the Emby provider implemented as a JVM Kotlin module (14 files in `providers/emby/`). The goal is to embed QuickJS into the app and run providers as JavaScript loaded at runtime. Provider source lives in `providers-ts/` as structured TypeScript, compiled to a single `.js` bundle.

Storage stays per-platform: Room on Android. No changes to storage.

## Architecture Overview

```
┌───────────────────────────────────────────────────────┐
│  OpenTune Android App                                  │
│  ┌───────────────┐    ┌─────────────────────────────┐  │
│  │  UI Layer       │    │  :provider-js (generic)     │  │
│  │  Compose TV     │    │  ┌───────────────────────┐  │  │
│  │  Navigation     │    │  │  QuickJS Engine (JNI)  │  │  │
│  └───────┬─────────┘    │  └──────────┬────────────┘  │  │
│          │              │  ┌──────────▼────────────┐  │  │
│          ├─────────────►│  │  JsProvider            │  │  │
│          │              │  │  JsProviderInstance     │  │  │
│          │              │  │  (both ProviderInstance │  │  │
│          │              │  │   and PlaybackHooks)    │  │  │
│          │              │  └──────────┬─────────────┘  │  │
│          │              │  ┌──────────▼────────────┐  │  │
│          │              │  │  emby-provider.js      │  │  │
│          │              │  │  (bundled, from TS)    │  │  │
│          │              │  └─────────────────────────┘  │  │
│          │              └──────────────────────────────┘  │
│          │                                                 │
│  Storage: Room — unchanged                                 │
└──────────┼─────────────────────────────────────────────────┘
           │
     JS ↔ Host: JSON strings + QuickJS Promise resolution
```

## File Architecture

```
pauser/
├── app/
│   ├── build.gradle.kts                 # add :provider-js dep, CMake build
│   ├── src/main/jni/
│   │   ├── CMakeLists.txt               # builds libopentune_js_jni.so
│   │   ├── quickjs_engine.c             # QuickJS C API wrapper
│   │   └── quickjs_ng/                  # quickjs-ng (vendored or git submodule)
│   └── src/main/assets/providers/
│       └── emby-provider.js             # bundled from providers-ts/
│
├── providers-ts/
│   ├── package.json
│   ├── tsconfig.json
│   ├── rollup.config.js                 # bundles each provider to single .js
│   ├── src/
│   │   └── types.ts                     # shared types: HostAPI, contracts, etc.
│   └── emby/
│       ├── package.json
│       ├── index.ts                     # export { provider } — entry point
│       ├── provider.ts                  # getFieldsSpec, validateFields, bootstrap
│       ├── instance.ts                  # loadBrowsePage, searchItems, loadDetail, resolvePlayback
│       ├── hooks.ts                     # onPlaybackReady, onProgressTick, onStop
│       ├── api.ts                       # HTTP calls (auth, items, playback, sessions)
│       ├── urls.ts                      # normalizeBaseUrl, imageUrl, resolvePlaybackUrl
│       ├── mapper.ts                    # BaseItemDto → MediaListItem, etc.
│       ├── device-profile.ts            # buildDeviceProfile from CodecCapabilities
│       └── dto.ts                       # TypeScript types for Emby API responses
│
├── provider-js/
│   ├── build.gradle.kts                 # Kotlin/JVM library
│   └── src/main/java/com/opentune/provider/js/
│       ├── QuickJsEngine.kt             # JNI wrapper: create/destroy runtime+context
│       ├── JsProvider.kt                # implements OpenTuneProvider (generic)
│       ├── JsProviderInstance.kt        # implements OpenTuneProviderInstance + OpenTunePlaybackHooks
│       ├── HostApis.kt                  # host.http, host.config, host.log, host.crypto
│       └── PluginScanner.kt             # scans plugins/ dir for .js files
│
├── provider-api/                        # UNCHANGED — Kotlin contract interfaces
│   └── src/main/java/com/opentune/provider/
│       ├── ProviderContracts.kt
│       ├── PlaybackContracts.kt
│       ├── CatalogContracts.kt
│       ├── PlatformContext.kt
│       └── CodecCapabilities.kt
│
├── storage/                             # UNCHANGED
└── player/                              # UNCHANGED
```

Key principle: **No provider-specific keywords appear outside `providers-ts/`.** The `provider-js/` module has no knowledge of Emby, SMB, Telegram, or any specific provider. It only knows the generic contract (`OpenTuneProvider`, `OpenTuneProviderInstance`, `OpenTunePlaybackHooks`).

---

## providers-ts/emby/ — Structured TypeScript Source

The Emby provider lives as a well-organized TypeScript project, not a single file. A bundler (Rollup/esbuild) compiles it to a single `emby-provider.js`.

### Entry Point (`index.ts`)

```typescript
// providers-ts/emby/index.ts
export { provider } from './provider';
```

### Provider Factory (`provider.ts`)

```typescript
// providers-ts/emby/provider.ts
import { OpenTuneProvider, ValidationResult, ServerFieldSpec, PlatformContext, CodecCapabilities } from '../src/types';
import { normalizeBaseUrl } from './urls';
import { authenticate, getSystemInfo } from './api';

export const provider: OpenTuneProvider = {
    type: "emby",
    providesCover: true,

    getFieldsSpec(): ServerFieldSpec[] {
        return [
            { id: "base_url", labelKey: "fld_http_library_url", kind: "SingleLineText", required: true, order: 0, placeholderKey: "ph_http_library_url" },
            { id: "username", labelKey: "fld_account_username", kind: "SingleLineText", required: true, order: 1 },
            { id: "password", labelKey: "fld_account_password", kind: "Password", required: true, sensitive: true, order: 2 },
        ];
    },

    async validateFields(values: Record<string, string>): Promise<ValidationResult> {
        try {
            const baseUrl = normalizeBaseUrl(values.base_url);
            const auth = await authenticate(baseUrl, values.username, values.password);
            const token = auth.AccessToken;
            if (!token) return { type: "error", message: "No access token returned" };
            const userId = auth.User?.Id;
            if (!userId) return { type: "error", message: "No user id returned" };

            const info = await getSystemInfo(baseUrl, token);
            const hash = host.crypto.sha256(baseUrl + userId);

            return {
                type: "success",
                hash,
                displayName: info.ServerName || baseUrl,
                fieldsJson: JSON.stringify({ base_url: baseUrl, user_id: userId, access_token: token, server_id: info.Id }),
            };
        } catch (e) {
            return { type: "error", message: String((e as Error).message || e) };
        }
    },

    createInstance(fieldsJson: string, caps: CodecCapabilities): ProviderInstance {
        const config = JSON.parse(fieldsJson);
        return createInstance(config, caps);
    },

    async bootstrap(ctx: PlatformContext): Promise<void> {
        // store device identification for EmbyClientIdentification headers
        host.log.info(`Emby bootstrap: device=${ctx.deviceName}`);
    },
};
```

### Instance (`instance.ts`)

```typescript
// providers-ts/emby/instance.ts
// Implements: loadBrowsePage, searchItems, loadDetail, resolvePlayback, withStream
import { ProviderInstance, BrowsePageResult, PlaybackSpec } from '../src/types';
import { getViews, getItems, getItem, getPlaybackInfo } from './api';
import { toListItem } from './mapper';
import { BROWSE_FIELDS, DETAIL_FIELDS, NON_PLAYABLE_TYPES } from './provider';
import { resolvePlaybackUrl } from './urls';
import { buildSubtitleTracks } from './hooks';
import { buildDeviceProfile } from './device-profile';

// ... createInstance() returns the ProviderInstance with all methods
```

### Playback Hooks (`hooks.ts`)

```typescript
// providers-ts/emby/hooks.ts
// The playback hooks that get called by the host at player lifecycle events.
// These are returned as part of the instance object — not a separate Kotlin class.

import { reportPlaying, reportProgress, reportStopped } from './api';

export function createPlaybackHooks(config: EmbyConfig, sessionData: PlaybackSessionData) {
    return {
        progressIntervalMs(): number { return 10000; },

        async onPlaybackReady(positionMs: number, playbackRate: number): Promise<void> {
            const ticks = positionMs * 10000;
            await reportPlaying(config, sessionData, ticks, playbackRate);
        },

        async onProgressTick(positionMs: number, playbackRate: number): Promise<void> {
            const ticks = positionMs * 10000;
            await reportProgress(config, sessionData, ticks, playbackRate);
        },

        async onStop(positionMs: number): Promise<void> {
            const ticks = positionMs * 10000;
            await reportStopped(config, sessionData, ticks);
        },
    };
}

export function buildSubtitleTracks(source: MediaSourceInfo, config: EmbyConfig, itemId: string, caps: CodecCapabilities): SubtitleTrack[] {
    // ... same logic as current Kotlin EmbyProviderInstance subtitle building
}
```

### API (`api.ts`)

```typescript
// providers-ts/emby/api.ts
// All HTTP calls to the Emby API. Uses host.http.* exclusively.

import { AuthResult, SystemInfo, ItemsResult, PlaybackInfoResponse, BaseItemDto } from './dto';

async function embyHeaders(token?: string): Promise<Record<string, string>> {
    const headers: Record<string, string> = { "Accept": "application/json" };
    if (token) headers["X-Emby-Token"] = token;
    return headers;
}

async function assertOk(resp: HttpResponse): Promise<string> {
    if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${await resp.text()}`);
    return await resp.text();
}

export async function authenticate(baseUrl: string, username: string, password: string): Promise<AuthResult> {
    const resp = await host.http.post(`${baseUrl}/Users/AuthenticateByName`, {
        Username: username, Pw: password,
    }, embyHeaders());
    return JSON.parse(await assertOk(resp));
}

export async function getSystemInfo(baseUrl: string, token: string): Promise<SystemInfo> {
    const resp = await host.http.get(`${baseUrl}/System/Info`, embyHeaders(token));
    return JSON.parse(await assertOk(resp));
}

// ... getViews, getItems, getItem, getPlaybackInfo, reportPlaying, reportProgress, reportStopped
```

### Other files

- `dto.ts` — TypeScript interfaces matching Emby API response shapes
- `urls.ts` — `normalizeBaseUrl`, `imageUrl`, `resolvePlaybackUrl`
- `mapper.ts` — `BaseItemDto → MediaListItem` conversion
- `device-profile.ts` — `buildDeviceProfile(CodecCapabilities)` → Emby DeviceProfile JSON

### Build

```bash
# providers-ts/package.json
{
    "scripts": {
        "build": "rollup -c"
    }
}

# rollup.config.js — one bundle per provider
export default {
    input: { 'emby-provider': 'emby/index.ts' },
    plugins: [typescript(), resolve(), json()],
    output: {
        dir: '../app/src/main/assets/providers/',
        format: 'iife',
        exports: 'named',
    },
};
```

Produces `app/src/main/assets/providers/emby-provider.js` — a single IIFE that exposes `window.opentuneProvider` (or `export const provider` if QuickJS supports ES module eval).

---

## provider-js/ — Generic Kotlin Adapter

### JsProvider.kt

```kotlin
// provider-js/src/main/java/com/opentune/provider/js/JsProvider.kt
// Completely provider-agnostic. No "emby", "smb", or any provider name.

class JsProvider(
    private val engine: QuickJsEngine,
) : OpenTuneProvider {

    override val providerType: String by lazy {
        engine.callSync("provider.type")
    }

    override val providesCover: Boolean by lazy {
        engine.callSync("provider.providesCover")
    }

    override fun getFieldsSpec(): List<ServerFieldSpec> =
        Json.decodeFromString(engine.callSync("provider.getFieldsSpec()"))

    override suspend fun validateFields(values: Map<String, String>): ValidationResult {
        val json = Json.encodeToString(values)
        val resultJson = engine.callAsync("provider.validateFields", json)
        return Json.decodeFromString(resultJson)
    }

    override fun createInstance(values: Map<String, String>, capabilities: CodecCapabilities): OpenTuneProviderInstance {
        val argsJson = buildJsonObject {
            put("fields", Json.encodeToString(values))
            put("capabilities", Json.encodeToString(capabilities))
        }
        val instanceId = engine.callSync<Long>("provider.createInstance", argsJson)
        return JsProviderInstance(engine, instanceId)
    }

    override fun bootstrap(context: PlatformContext) {
        val json = Json.encodeToString(mapOf(
            "deviceName" to context.deviceName,
            "deviceId" to context.deviceId,
            "clientVersion" to context.clientVersion,
            "cacheDir" to context.cacheDir.absolutePath,
        ))
        engine.callSync("provider.bootstrap", json)
    }
}
```

### JsProviderInstance.kt

```kotlin
// provider-js/src/main/java/com/opentune/provider/js/JsProviderInstance.kt
// Implements BOTH OpenTuneProviderInstance AND OpenTunePlaybackHooks.
// Provider-agnostic — forwards all calls to the JS context.

class JsProviderInstance(
    private val engine: QuickJsEngine,
    private val instanceId: Long,
) : OpenTuneProviderInstance, OpenTunePlaybackHooks {

    // ── OpenTuneProviderInstance ──────────────────────────────────

    override suspend fun loadBrowsePage(location: String?, startIndex: Int, limit: Int): BrowsePageResult {
        val argsJson = buildJsonObject {
            put("instanceId", instanceId)
            put("location", location)
            put("startIndex", startIndex)
            put("limit", limit)
        }
        val resultJson = engine.callAsync("instance.loadBrowsePage", argsJson)
        return Json.decodeFromString(resultJson)
    }

    override suspend fun searchItems(scopeLocation: String, query: String): List<MediaListItem> {
        val argsJson = buildJsonObject {
            put("instanceId", instanceId)
            put("scopeLocation", scopeLocation)
            put("query", query)
        }
        val resultJson = engine.callAsync("instance.searchItems", argsJson)
        return Json.decodeFromString(resultJson)
    }

    override suspend fun loadDetail(itemRef: String): MediaDetailModel {
        val argsJson = buildJsonObject {
            put("instanceId", instanceId)
            put("itemRef", itemRef)
        }
        val resultJson = engine.callAsync("instance.loadDetail", argsJson)
        return Json.decodeFromString(resultJson)
    }

    override suspend fun resolvePlayback(itemRef: String, startMs: Long): PlaybackSpec {
        val argsJson = buildJsonObject {
            put("instanceId", instanceId)
            put("itemRef", itemRef)
            put("startMs", startMs)
        }
        val resultJson = engine.callAsync("instance.resolvePlayback", argsJson)
        return Json.decodeFromString(resultJson)
    }

    override suspend fun <T> withStream(itemRef: String, block: suspend (ItemStream) -> T): T? {
        // JS plugins don't support ItemStream bridge
        return null
    }

    // ── OpenTunePlaybackHooks ─────────────────────────────────────

    override fun progressIntervalMs(): Long {
        val resultJson = engine.callSync("instance.progressIntervalMs", instanceId)
        return Json.decodeFromString(resultJson)
    }

    override suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float) {
        val argsJson = buildJsonObject {
            put("instanceId", instanceId)
            put("positionMs", positionMs)
            put("playbackRate", playbackRate)
        }
        engine.callAsync("instance.onPlaybackReady", argsJson)
    }

    override suspend fun onProgressTick(positionMs: Long, playbackRate: Float) {
        val argsJson = buildJsonObject {
            put("instanceId", instanceId)
            put("positionMs", positionMs)
            put("playbackRate", playbackRate)
        }
        engine.callAsync("instance.onProgressTick", argsJson)
    }

    override suspend fun onStop(positionMs: Long) {
        val argsJson = buildJsonObject {
            put("instanceId", instanceId)
            put("positionMs", positionMs)
        }
        engine.callAsync("instance.onStop", argsJson)
    }
}
```

The player module receives this `JsProviderInstance` as `PlaybackSpec.hooks` — it's already an `OpenTunePlaybackHooks`, no adapter needed.

### QuickJsEngine.kt

```kotlin
// provider-js/src/main/java/com/opentune/provider/js/QuickJsEngine.kt
// Generic JS engine wrapper. No provider knowledge.

class QuickJsEngine(
    private val httpClient: OkHttpClient,
    private val platformContext: PlatformContext,
) : AutoCloseable {
    private var ctxPtr: Long = 0L
    private val pendingPromises = mutableMapOf<Long, CompletableDeferred<String>>()

    init {
        ctxPtr = nativeInit()
        HostApis.inject(ctxPtr, httpClient, platformContext)
    }

    fun loadSource(source: String) {
        nativeEval(ctxPtr, source)
    }

    fun callSync(methodPath: String, argsJson: String? = null): String {
        return nativeCallMethod(ctxPtr, methodPath, argsJson ?: "null")
    }

    suspend fun callAsync(methodPath: String, argsJson: String): String {
        return suspendCancellableCoroutine { cont ->
            val result = nativeCallMethod(ctxPtr, methodPath, argsJson)
            if (isPromise(result)) {
                val promiseId = parsePromiseId(result)
                pendingPromises[promiseId] = CompletableDeferred()
                nativeExecutePendingJobs(ctxPtr, 1)
                launch {
                    val resolved = pendingPromises[promiseId]!!.await()
                    cont.resume(resolved)
                    pendingPromises.remove(promiseId)
                }
            } else {
                cont.resume(result)
            }
        }
    }

    fun onPromiseResolved(promiseId: Long, resultJson: String) {
        pendingPromises[promiseId]?.complete(resultJson)
    }

    override fun close() {
        if (ctxPtr != 0L) nativeDestroy(ctxPtr)
    }

    private external fun nativeInit(): Long
    private external fun nativeDestroy(ctxPtr: Long)
    private external fun nativeEval(ctxPtr: Long, source: String): String
    private external fun nativeCallMethod(ctxPtr: Long, methodPath: String, argsJson: String): String
    private external fun nativeExecutePendingJobs(ctxPtr: Long, maxJobs: Int): Int
}
```

---

## Host APIs (`HostApis.kt`)

```kotlin
// provider-js/src/main/java/com/opentune/provider/js/HostApis.kt
// Registers host.* objects into the QuickJS context. Provider-agnostic.

object HostApis {
    fun inject(ctxPtr: Long, httpClient: OkHttpClient, platformCtx: PlatformContext) {
        // host.http — get, post, put, delete (all return Promises)
        // host.config — deviceName, deviceId, clientVersion, cacheDir, codecCapabilities
        // host.log — info, warn, error (synchronous)
        // host.crypto — sha256 (synchronous)
    }
}
```

HTTP implementation bridges to OkHttp:

```kotlin
// When JS calls host.http.get(url, headers):
// 1. C layer receives call → calls back into Kotlin via JNI
// 2. Kotlin launches OkHttp call on Dispatchers.IO
// 3. When response arrives, Kotlin calls nativeResolvePromise(ctxPtr, promiseId, responseJson, isError)
// 4. C layer resolves the JS Promise → JS async function resumes
```

---

## QuickJS JNI Layer (C)

```c
// app/src/main/jni/quickjs_engine.c

jlong Java_com_opentune_provider_js_QuickJsEngine_nativeInit(JNIEnv*, jobject);
void Java_com_opentune_provider_js_QuickJsEngine_nativeDestroy(JNIEnv*, jobject, jlong ctxPtr);
jstring Java_com_opentune_provider_js_QuickJsEngine_nativeEval(JNIEnv*, jobject, jlong ctxPtr, jstring source);
jstring Java_com_opentune_provider_js_QuickJsEngine_nativeCallMethod(JNIEnv*, jobject, jlong ctxPtr, jstring methodPath, jstring argsJson);
jint Java_com_opentune_provider_js_QuickJsEngine_nativeExecutePendingJobs(JNIEnv*, jobject, jlong ctxPtr, jint maxJobs);
void Java_com_opentune_provider_js_QuickJsEngine_nativeResolvePromise(JNIEnv*, jobject, jlong ctxPtr, jlong promiseId, jstring resultJson, jboolean isError);
```

The C layer:
1. `JS_NewRuntime()` + `JS_NewContext()` on init
2. Injects `host` global with methods that call back into Kotlin via JNI callbacks
3. `JS_Eval()` loads the provider source
4. `nativeCallMethod` parses `methodPath` (e.g., `"provider.validateFields"`, `"instance.loadBrowsePage"`) → looks up the JS function → calls it with parsed args → returns JSON string
5. If the JS function returns a Promise, returns `{"__promise": <id>}` — Kotlin detects this and enters the async loop

---

## Integration: How the App Uses JS Providers

### OpenTuneApplication

```kotlin
class OpenTuneApplication : Application() {
    override fun onCreate() {
        // ... existing Room + storage setup ...

        // Load JS provider from assets
        val jsSource = assets.open("providers/emby-provider.js").bufferedReader().readText()

        val engine = QuickJsEngine(
            httpClient = OkHttpClient.Builder().build(),
            platformContext = AndroidPlatformContext(this),
        )
        engine.loadSource(jsSource)

        val jsProvider = JsProvider(engine)
        providerRegistry.register(jsProvider.providerType, jsProvider)
        jsProvider.bootstrap(AndroidPlatformContext(this))

        // ... rest of existing setup ...
    }
}
```

### OpenTuneProviderRegistry — Minor Change

Add a `register(providerType: String, provider: OpenTuneProvider)` method so the app can programmatically register JS providers alongside ServiceLoader-discovered ones.

### Player Integration — No Changes

`PlaybackSpecExt.kt` receives `PlaybackSpec` from `JsProviderInstance.resolvePlayback()`. The `hooks` field is already an `OpenTunePlaybackHooks` (the same `JsProviderInstance`), so the player calls `hooks.onPlaybackReady/onProgressTick/onStop` directly. No adapter, no provider-specific Kotlin code.

---

## Migration Strategy

### Phase 1: QuickJS JNI Foundation

1. Add quickjs-ng to `app/src/main/jni/` (vendored or git submodule)
2. Write `quickjs_engine.c` — runtime/context creation, JS eval, method invocation
3. Write `CMakeLists.txt` to build `libopentune_js_jni.so`
4. Write `QuickJsEngine.kt` — Kotlin wrapper over JNI
5. Test: evaluate `"1 + 1"` → verify returns `"2"`

### Phase 2: Host APIs

1. Implement `host.http.get/post` in JNI + Kotlin (OkHttp bridge with Promise resolution)
2. Implement `host.log.info/warn/error` (Logcat)
3. Implement `host.crypto.sha256` (MessageDigest)
4. Implement `host.config` (inject platform context values as JS properties)
5. Test: JS calls `host.http.get("https://httpbin.org/get")` → verify response

### Phase 3: TypeScript Project + Emby Port

1. Create `providers-ts/` — TypeScript project with `tsconfig.json`, Rollup config, shared `types.ts`
2. Create `providers-ts/emby/` — port all 14 Kotlin files to structured TypeScript
3. Build to `app/src/main/assets/providers/emby-provider.js`
4. Test: run `npm run build`, verify bundle loads and `provider.type === "emby"`

### Phase 4: Generic Kotlin Adapter

1. Write `JsProvider` — implements `OpenTuneProvider`, forwards all calls to JS
2. Write `JsProviderInstance` — implements `OpenTuneProviderInstance` + `OpenTunePlaybackHooks`
3. Test: load `emby-provider.js`, call `getFieldsSpec()` → verify 3 fields match current Kotlin Emby

### Phase 5: Wire Into App + Compare

1. Register `JsProvider` alongside current Kotlin `EmbyProvider` (both active)
2. Add server via JS provider — verify validation produces identical `ValidationResult`
3. Browse, search, detail, playback — verify behavior matches Kotlin Emby
4. Verify playback hooks: `onPlaybackReady` fires, progress tick works, `onStop` reports to server
5. Feature flag: `useJsEmby = true/false` to switch between implementations
6. When `useJsEmby = true` and all flows match → Phase 6

### Phase 6: Remove Old Emby Module

1. Remove `providers/emby/` Gradle module
2. Remove `:providers:emby` from `settings.gradle.kts`
3. Remove `useJsEmby` feature flag
4. Remove Emby ServiceLoader registration

---

## What Changes vs What Stays

### New
- `app/src/main/jni/` — QuickJS engine C code + quickjs-ng
- `provider-js/` — generic Kotlin adapter module (no provider names)
- `providers-ts/` — TypeScript source for providers
- `app/src/main/assets/providers/` — bundled `.js` files

### Modified
- `app/build.gradle.kts` — add CMake build, `:provider-js` dep
- [OpenTuneApplication.kt](app/src/main/java/com/opentune/app/OpenTuneApplication.kt) — load JS provider from assets, register
- [OpenTuneProviderRegistry.kt](app/src/main/java/com/opentune/app/providers/OpenTuneProviderRegistry.kt) — add `register()` method

### Unchanged
- `storage/` — Room, all DAOs, entities
- `player/` — Media3 integration, `PlaybackSpecExt.kt`
- `provider-api/` — Kotlin contract interfaces
- [ProviderInstanceRegistry.kt](app/src/main/java/com/opentune/app/providers/ProviderInstanceRegistry.kt) — works with any `OpenTuneProviderInstance`
- [ServerConfigRepository.kt](app/src/main/java/com/opentune/app/providers/ServerConfigRepository.kt) — calls `provider.validateFields()` unchanged
- All Compose routes (BrowseRoute, DetailRoute, PlayerRoute)
- `providers/smb/` — stays as JVM provider (not ported to JS)

---

## Key Challenges

### 1. Promise Job Pump

QuickJS async requires the host to run `JS_ExecutePendingJob()` after each JS→host interaction. If missed, promises hang.

**Solution:** `callAsync` wraps every async call in `suspendCancellableCoroutine`. After the initial JS call returns a Promise token, it runs `nativeExecutePendingJobs` and registers a `CompletableDeferred`. When the native OkHttp callback fires, it calls `nativeResolvePromise` which completes the deferred → Kotlin coroutine resumes.

### 2. Playback Hooks Are Part of the Instance

The player needs `OpenTunePlaybackHooks` with `onPlaybackReady/onProgressTick/onStop`. These are called by the Media3 player lifecycle at arbitrary times (not during a provider method call).

**Solution:** `JsProviderInstance` implements both `OpenTuneProviderInstance` and `OpenTunePlaybackHooks`. The JS provider's instance object includes `onPlaybackReady`, `onProgressTick`, `onStop` methods. `JsProviderInstance` forwards these to the JS context on each player lifecycle event. Since the JS context holds the instance, it has access to the correct config (baseUrl, token, userId) to make the report calls.

### 3. DeviceProfile is Complex but Mechanical

The Kotlin `DeviceProfile` has deeply nested structures. Porting it to TypeScript is tedious but straightforward — it's just building a JSON object.

**Solution:** `providers-ts/emby/device-profile.ts` replicates the exact same JSON structure. Both sides use the same field names so the JSON roundtrips to Emby's API correctly.

### 4. EmbyClientIdentification Headers

The current Kotlin provider uses a global `EmbyClientIdentificationStore` for the OkHttp interceptor.

**Solution:** No global store needed. The JNI HTTP bridge reads `host.config.deviceName`/`deviceId`/`clientVersion` and adds the correct `Authorization` / `X-Emby-Authorization` / `X-Emby-Token` headers per-request.

### 5. JS Instance Lifecycle

`JsProviderInstance` holds a `ctxPtr` and `instanceId`. If the QuickJS context is destroyed (e.g., on app kill), the instance handle becomes invalid.

**Solution:** `JsProviderInstance` checks `ctxPtr` validity before every call. If invalid, throws a clear error. The context is only destroyed in `QuickJsEngine.close()` which is called on app teardown — after all instances are no longer in use.

---

## Verification

### Phase 1
1. Build and run app with QuickJS JNI
2. Logcat: "QuickJS runtime initialized"
3. `nativeEval("1 + 1")` returns `"2"`

### Phase 2
1. JS: `host.http.get("https://httpbin.org/get")` → returns 200 with JSON body
2. JS: `host.crypto.sha256("test")` → returns `"9f86d..."`
3. JS: `host.log.info("hello")` → appears in Logcat

### Phase 3
1. `npm run build` → produces `emby-provider.js`
2. Load bundle → `provider.type === "emby"`
3. `provider.getFieldsSpec()` → 3 field specs match current Kotlin Emby

### Phase 4
1. `JsProvider.validateFields()` → returns `ValidationResult.Success` with correct hash
2. `JsProviderInstance.loadBrowsePage(null, 0, 20)` → `BrowsePageResult` with items
3. `JsProviderInstance.resolvePlayback(itemId, 0)` → `PlaybackSpec` with URL
4. `JsProviderInstance.progressIntervalMs()` → `10000`
5. `JsProviderInstance.onPlaybackReady(0, 1.0f)` → no crash, forwards to JS

### Phase 5
1. `useJsEmby = true` — add server, browse, play, verify identical behavior to Kotlin Emby
2. Toggle `useJsEmby = false` — Kotlin Emby still works (both coexist)

### Phase 6
1. Remove `:providers:emby` module, verify app builds
2. Full end-to-end: add server → browse → detail → play → progress → stop → re-launch → resume