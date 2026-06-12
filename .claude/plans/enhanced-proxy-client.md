# Plan: Proxy Client Contract, Proxy Ctrl UI, and Clash Controller

## Context

The current proxy integration lets `:app` inject a proxied `OkHttpClient` into content endpoint clients. Providers use that client for data requests, playback specs, and JS `host.http` calls. The new guide in `docs/proxy-ui.prompt.md` asks to make the proxy layer more explicit and controllable: proxy providers should validate by creating a client and calling `client.test()`, endpoint clients should receive a `ProxyClient` instead of only a raw `OkHttpClient`, JS providers should receive proxy configuration in bootstrap data, and proxy providers should be able to expose a `CtrlUI()` surface. The first concrete `CtrlUI()` implementation is a new Clash controller provider with refreshable proxy-line chips and latency indicators.

The intended outcome is a typed proxy abstraction that preserves today's transparent HTTP behavior while also giving providers and users access to proxy metadata and provider-specific control panels.

## Recommended approach

### 1. Introduce `ProxyClient` in the proxy contract

Modify `proxy/contract/src/main/java/com/opentune/proxy/contract/ProxyProvider.kt`:

- Add a `ProxyClient` interface with:
  - `fun getHttpClient(): OkHttpClient`
  - `fun getConfig(): Map<String, String>`
  - `suspend fun test(): ProxyValidationResult`
- Change `ProxyProvider.createClient(values)` to return `ProxyClient` instead of `OkHttpClient`.
- Remove `ProxyProvider.validateFields(values)`.
- Add the UI hook required by the guide:
  - `val hasCtrlUI: Boolean get() = false`
  - `@Composable fun CtrlUI(...) {}` with enough context for a provider to render and navigate to edit.

Use a simple `Map<String, String>` for `getConfig()` first because the guide says `.getConfig()` and existing proxy fields are already persisted as maps. Avoid over-designing a typed config until Clash and JS reveal stable shape.

### 2. Port HTTP proxy to the new client style

Modify `proxy/providers/http/src/main/java/com/opentune/proxy/http/HttpProxyProvider.kt`:

- Replace `validateFields()` with an `HttpProxyClient.test()` implementation.
- Move the existing host/port parsing, validation, OkHttp proxy builder, and test request behavior into the client.
- Keep `HttpProxyProvider.getFieldsSpec()` unchanged.
- Make `createClient(values)` return `HttpProxyClient`.
- `HttpProxyClient.getHttpClient()` should return the same proxied `OkHttpClient` currently produced by `createClient()`.
- `HttpProxyClient.getConfig()` should include at least `type`, `host`, `port`, `username`, and any non-sensitive values needed by JS providers. Be deliberate about whether to expose `password`; if included, document that this is in-process bootstrap data only.

### 3. Update proxy add/edit validation to use `client.test()`

Modify `content/ui/src/main/java/com/opentune/content/ui/providers/ProxyRepository.kt`:

- In `submitAdd()` and `submitEdit()`, replace `proxyProvider.validateFields(values)` with:
  - create proxy client via `proxyProvider.createClient(values)`
  - call `client.test()`
  - persist `ProxyValidationResult.Success.name` and `.fields` exactly as today
- Preserve current storage behavior, duplicate handling, and endpoint-client invalidation after proxy edits.

This mirrors the existing endpoint validation pattern in `content/ui/src/main/java/com/opentune/content/ui/providers/EndpointConfigRepository.kt`, where `buildClient()` is followed by `client.test()`.

### 4. Inject `ProxyClient` into content endpoints while preserving `httpClient`

Modify `content/contract/src/main/java/com/opentune/content/contract/ProviderContracts.kt`:

- Add `open var proxyClient: ProxyClient? = null` to `EndpointClient`.
- Keep `open var httpClient: OkHttpClient = OkHttpClient()` for compatibility with existing native providers.
- The registry should set both:
  - `client.proxyClient = proxyClient`
  - `client.httpClient = proxyClient?.getHttpClient() ?: OkHttpClient()`

Modify `content/contract/src/main/java/com/opentune/content/contract/RegistryHolders.kt`:

- Add `suspend fun buildProxyClient(proxyId: String?): ProxyClient?` to `EndpointClientAccess`.
- Keep `buildHttpClient(proxyId)` as a compatibility helper returning `buildProxyClient(proxyId)?.getHttpClient() ?: OkHttpClient()` because existing form/QR flows currently call it.

Modify `app/src/main/java/com/opentune/app/providers/EndpointClientRegistry.kt`:

- Add `buildProxyClient(proxyId)` that loads the stored proxy entity, decodes fields JSON, resolves the provider, and calls `createClient(fields)`.
- Update `buildClient(entity)` to use `ProxyClient` as the source of both injected `proxyClient` and `httpClient`.
- Continue using the derived `httpClient` for Coil `ImageLoader` via `OkHttpNetworkFetcherFactory`.

Modify `content/ui/src/main/java/com/opentune/content/ui/providers/EndpointConfigRepository.kt` and `content/ui/src/main/java/com/opentune/content/ui/ContentRoutes.kt` only as needed:

- Prefer setting both `proxyClient` and `httpClient` in endpoint test/QR temporary clients.
- Reuse `EndpointClientRegistryHolder.get().buildProxyClient(proxyId)` where the caller needs proxy metadata; keep `buildHttpClient()` for pure HTTP callers.

### 5. Pass proxy config to JS providers and keep `host.http` proxied

Modify `content/providers/js/src/main/java/com/opentune/provider/js/JsClient.kt`:

- Use `proxyClient?.getHttpClient() ?: httpClient` when creating `QuickJsEngine` in `ensureReady()` and temporary `withEngine()` paths.
- Include proxy config in the init/bootstrap data built around `ensureReady()`:
  - current init args contain `credentials` and `deviceInfo`
  - add `proxyConfig` from `proxyClient?.getConfig()` or JSON `null`
- Ensure initialization stays safe if no proxy is configured.

Modify `content/providers/js/src/main/java/com/opentune/provider/js/JsProvider.kt`:

- Extend `HOST_BOOTSTRAP_JS` so JS code has access to the bootstrap proxy config.
- Keep `host.http` using the already-proxied engine HTTP stack, so existing JS providers continue to work transparently.
- Add a small wrapper/helper in the bootstrap only if needed to expose proxy config ergonomically, for example `globalThis.host.proxyConfig` or `globalThis.__proxyConfig`.

Do not change `HostApis` unless implementation shows the JS host dispatch needs a separate proxy namespace. The existing `QuickJsEngine(hostApis, httpClient)` path should already make `host.http.get/post/put/delete` use the supplied client.

### 6. Add `CtrlUI()` wiring for proxy providers

Modify `app/src/main/java/com/opentune/app/ui/home/HomeRoute.kt`:

- Resolve each proxy entity's provider through `app.proxyProviderRegistry.proxy(proxy.proxyType)`.
- Change proxy button click behavior:
  - if `provider.hasCtrlUI`, open that provider's `CtrlUI()`
  - otherwise keep the current practical behavior of opening the edit page
- Keep the TV menu-key behavior with `Modifier.onTvMenuKeyDown { onEditProxy(proxy.proxyType, proxy.id) }`, matching the existing endpoint edit pattern.
- Pass an edit callback into `CtrlUI()` so providers such as Clash can implement the settings icon by navigating to the existing edit page.

Prefer rendering `CtrlUI()` inline from `HomeRoute` as an overlay/dialog state instead of adding a navigation route. This keeps the new behavior local to the home screen, while the existing edit form stays in `proxy/ui/src/main/java/com/opentune/proxy/ui/ProxyRoutes.kt`.

### 7. Implement the Clash controller proxy provider

Add a new module under `proxy/providers/clash/`, mirroring the structure of `proxy/providers/http/`:

- `ClashProxyProvider`
  - `proxyType = "clash"`
  - fields: `url`, `secret`, `name`
  - `hasCtrlUI = true`
  - `createClient(values)` returns `ClashProxyClient`
  - registers via the same ServiceLoader pattern used by existing proxy providers
- `ClashProxyClient`
  - `getHttpClient()` returns an `OkHttpClient` configured to use the active Clash local proxy endpoint
  - `getConfig()` exposes controller URL, selected proxy line, and local proxy endpoint details needed by JS providers
  - `test()` verifies the controller URL/secret and returns normalized fields/name
  - helper methods for `refresh`, latency test, and setting the active proxy line
- `ClashCtrlUI`
  - header row: refresh button, subscription URL input, settings icon
  - subscription URL input should reuse the two-stage active-input behavior from `:core:form` rather than inventing a separate interaction model
  - lazy grid of proxy-line chips using the existing `LazyVerticalGrid` style found in browse/search screens
  - chip content: line name centered, latency small at bottom-right
  - chip background: green under 100ms, yellow under 300ms, red above 300ms, neutral/grey for unknown or failed
  - active proxy chip gets a highlighted border
  - chip click calls the client/controller API to set the active proxy

Add the Clash module to the relevant Gradle settings/build files and include any needed dependencies already used elsewhere for Compose TV/material/foundation and OkHttp.

Keep network work off the UI thread. Use coroutine scope/state in `CtrlUI()` and run latency checks concurrently with bounded fan-out if the subscription returns many lines.

## Critical files to modify

- `proxy/contract/src/main/java/com/opentune/proxy/contract/ProxyProvider.kt`
- `proxy/providers/http/src/main/java/com/opentune/proxy/http/HttpProxyProvider.kt`
- `content/ui/src/main/java/com/opentune/content/ui/providers/ProxyRepository.kt`
- `content/contract/src/main/java/com/opentune/content/contract/ProviderContracts.kt`
- `content/contract/src/main/java/com/opentune/content/contract/RegistryHolders.kt`
- `app/src/main/java/com/opentune/app/providers/EndpointClientRegistry.kt`
- `content/ui/src/main/java/com/opentune/content/ui/providers/EndpointConfigRepository.kt`
- `content/ui/src/main/java/com/opentune/content/ui/ContentRoutes.kt`
- `content/providers/js/src/main/java/com/opentune/provider/js/JsClient.kt`
- `content/providers/js/src/main/java/com/opentune/provider/js/JsProvider.kt`
- `app/src/main/java/com/opentune/app/ui/home/HomeRoute.kt`
- `proxy/ui/src/main/java/com/opentune/proxy/ui/ProxyRoutes.kt` only if the edit route needs a new helper/callback; avoid route changes otherwise
- New representative files under `proxy/providers/clash/`
- Gradle settings/build files for registering the new Clash provider module

## Existing patterns to reuse

- Endpoint validation flow: `content/ui/src/main/java/com/opentune/content/ui/providers/EndpointConfigRepository.kt`
- Proxy persistence and endpoint invalidation: `content/ui/src/main/java/com/opentune/content/ui/providers/ProxyRepository.kt`
- Proxy edit form route: `proxy/ui/src/main/java/com/opentune/proxy/ui/ProxyRoutes.kt`
- TV menu-key edit behavior: `app/src/main/java/com/opentune/app/ui/home/HomeRoute.kt`
- JS init/bootstrap flow: `content/providers/js/src/main/java/com/opentune/provider/js/JsClient.kt` and `content/providers/js/src/main/java/com/opentune/provider/js/JsProvider.kt`
- Lazy grid UI examples: browse/search screens under `content/ui/src/main/java/com/opentune/content/ui/catalog/`
- Core form two-stage active input behavior: reuse or extract from `core/form/src/main/java/com/opentune/core/form/ProviderFormRoute.kt` / related form components rather than duplicating behavior

## Verification

1. Build the project after contract changes to catch all `createClient`, `validateFields`, `buildHttpClient`, and `EndpointClient` call sites.
2. Add/edit an HTTP proxy from the UI and confirm validation now flows through `ProxyClient.test()` and persisted fields/display name match the old behavior.
3. Add/edit an endpoint with no proxy and verify endpoint validation, browse, search, images, and playback still work.
4. Add/edit an endpoint with an HTTP proxy and verify:
   - endpoint `test()` uses the proxied client
   - images use the proxied `ImageLoader`
   - playback specs still resolve as before
5. Verify a JS provider:
   - `host.http` requests still use the proxied `OkHttpClient`
   - bootstrap/init data contains `proxyConfig` when a proxy is selected and `null`/absent when not selected
6. On the home screen, verify proxy buttons:
   - regular HTTP proxy click falls back to edit or no-op per final implementation choice
   - menu key opens the existing edit form
   - Clash proxy click opens `CtrlUI()`
7. With a reachable Clash controller, verify:
   - provider `test()` succeeds/fails with clear messages
   - refresh fetches proxy lines from the subscription URL
   - latency tests update chip colors using the guide thresholds
   - clicking a chip sets the active proxy and highlights it
   - settings icon opens the existing proxy edit page
8. Run relevant automated checks, at minimum the Gradle compile/test task used by this project for affected modules, then do one manual app run for the HomeRoute and Clash UI behavior.
