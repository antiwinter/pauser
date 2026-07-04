# How the Player Gets a Proxied Playback URL

There are two distinct playback paths, and the proxy/credential story differs for each.

## Path A: HTTP-based providers (Emby, Catvod, etc.)

### 1. UI requests the PlaybackSpec

`PlayerRoute.kt` (`content/ui/src/main/java/com/insomnia/content/ui/catalog/PlayerRoute.kt`) calls:

```kotlin
val client = EndpointClientRegistryHolder.get().getOrCreate(endpointId)
val spec = client.getPlaybackSpec(itemRefDecoded, startMs)
```

### 2. Provider resolves the URL (JS side)

`providers-ts/providers/emby/instance.ts` `getPlaybackSpec()`:

- POSTs to `Items/{id}/PlaybackInfo` with auth headers
- Picks `TranscodingUrl` or `DirectStreamUrl` from `MediaSourceInfo`
- Returns the PlaybackSpec with:
  - **url** = `https://emby.host/Videos/{id}/stream.m3u8?...`
  - **headers** = `{ 'X-Emby-Token': credentials.accessToken }`

### 3. Proxy is injected at the OkHttpClient level (NOT the URL)

`EndpointClientRegistry.kt` (`app/src/main/java/com/insomnia/app/providers/EndpointClientRegistry.kt`) `buildClient()` calls `buildHttpClient(entity.proxyId)`:

```
ProxyEntity (from DB)
  → ProxyProviderRegistry.proxy(proxyType)
    → HttpProxyProvider.createClient(fields)
      → OkHttpClient.Builder()
          .proxy(Proxy(HTTP, host:port))
          .proxyAuthenticator { request → Proxy-Authorization: Basic <base64> }
```

This client is attached to `client.httpClient` and handed to the player via `PlaybackSpec.httpClient`. The proxy operates at the **OkHttp transport layer** — ExoPlayer's requests go to the normal URL, and OkHttp routes them through the proxy transparently.

### 4. Credentials are injected via OkHttp interceptor

`PlaybackSpecExt.kt` (`player/src/main/java/com/insomnia/player/engine/PlaybackSpecExt.kt`) `toMediaSource()`:

```kotlin
val factory = OkHttpDataSource.Factory(spec.httpClient)
    .setRequestProperties(spec.headers)  // X-Emby-Token etc.
```

Every manifest/segment request ExoPlayer makes gets these headers attached.

Sidecar subtitles get the same treatment in `SubtitleController.kt` (`player/src/main/java/com/insomnia/player/controller/SubtitleController.kt`) `prepareWithSidecar()` — a parallel `SingleSampleMediaSource` is built with the same header interceptor.

## Path B: SMB (no HTTP URL of its own → local stream proxy)

`SmbProviderInstance.kt` (`content/providers/smb/src/main/java/com/insomnia/smb/SmbProviderInstance.kt`) `getPlaybackSpec()`:

```kotlin
val url = StreamRegistrarHolder.get().registerStream(this, itemRef)
// → "http://127.0.0.1:7920/stream/{uuid}"
```

`StreamProxy.kt` (`server/src/main/java/com/insomnia/server/StreamProxy.kt`) — Ktor CIO server on port 7920:

- `/stream/{token}` route looks up the token → calls `instance.openStream(itemRef)` → pumps SMB bytes back with HTTP Range support
- No headers on the playback URL — the token IS the credential (in the path)
- `SmbPlaybackHooks` (`content/providers/smb/src/main/java/com/insomnia/smb/SmbPlaybackHooks.kt`) revokes the token on disposal, closing the SMB session

## Summary Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        PlayerRoute.kt                           │
│  client.getPlaybackSpec(itemRef)                                │
└────────────────────────────┬────────────────────────────────────┘
                             │
              ┌──────────────┴──────────────┐
              ▼                             ▼
      HTTP Provider (Emby)           SMB Provider
   ┌────────────────────┐      ┌─────────────────────────┐
   │ JS: instance.ts    │      │ SmbProviderInstance     │
   │  - PlaybackInfo API│      │  - registerStream()     │
   │  - resolveUrl()    │      │  → http://127.0.0.1:7920│
   │  - headers: token  │      │    /stream/{uuid}       │
   └────────┬───────────┘      └──────────┬──────────────┘
            │                             │
            ▼                             │
   PlaybackSpec {                  PlaybackSpec {
     url: emby.host/...              url: localhost:7920/...
     headers: {X-Emby-Token}         headers: {}
     httpClient: (proxied)           httpClient: (proxied)
   }                               }
            │                             │
            ▼                             ▼
   ┌──────────────────────────────────────────────────┐
   │              InsomniaPlayer / PlaybackEngine      │
   │  toMediaSource() → OkHttpDataSource.Factory      │
   │    .setRequestProperties(spec.headers)            │
   │    (interceptor injects headers on every request) │
   │                                                    │
   │  OkHttpClient:                                    │
   │    .proxy(HTTP, host:port)  ← if endpoint has proxy│
   │    .proxyAuthenticator      ← 407 → Basic auth     │
   └──────────────────────────────────────────────────┘
```

## Key Files

| Concern | File |
|---|---|
| Player URL request | `content/ui/src/main/java/com/insomnia/content/ui/catalog/PlayerRoute.kt` |
| PlaybackSpec data class | `player/src/main/java/com/insomnia/player/PlaybackContracts.kt` |
| Playback engine | `player/src/main/java/com/insomnia/player/engine/PlaybackEngine.kt` |
| Header injection for playback | `player/src/main/java/com/insomnia/player/engine/PlaybackSpecExt.kt` |
| Subtitle header injection | `player/src/main/java/com/insomnia/player/controller/SubtitleController.kt` |
| Provider contract | `content/contract/src/main/java/com/insomnia/content/contract/ProviderContracts.kt` |
| Endpoint client registry (proxy wiring) | `app/src/main/java/com/insomnia/app/providers/EndpointClientRegistry.kt` |
| Proxy repository | `content/ui/src/main/java/com/insomnia/content/ui/providers/ProxyRepository.kt` |
| Proxy contract | `proxy/contract/src/main/java/com/insomnia/proxy/contract/ProxyProvider.kt` |
| HTTP proxy provider | `proxy/providers/http/src/main/java/com/insomnia/proxy/http/HttpProxyProvider.kt` |
| Stream registrar contract | `content/contract/src/main/java/com/insomnia/content/contract/StreamRegistrar.kt` |
| Local stream proxy (Ktor server) | `server/src/main/java/com/insomnia/server/StreamProxy.kt` |
| Server bootstrap | `server/src/main/java/com/insomnia/server/InsomniaServer.kt` |
| SMB provider instance | `content/providers/smb/src/main/java/com/insomnia/smb/SmbProviderInstance.kt` |
| SMB playback hooks | `content/providers/smb/src/main/java/com/insomnia/smb/SmbPlaybackHooks.kt` |
| JS provider instance | `content/providers/js/src/main/java/com/insomnia/provider/js/JsProviderInstance.kt` |
| Emby URL utilities | `providers-ts/providers/emby/urls.ts` |
| Emby API client | `providers-ts/providers/emby/api.ts` |
| Emby playback spec | `providers-ts/providers/emby/instance.ts` |

## Key Insight

The proxy is **never embedded in the URL**. It's wired into the `OkHttpClient` at client construction time based on the endpoint's `proxyId`, and that same client is used for both provider API calls and ExoPlayer media requests.

Credentials flow through two mechanisms:

1. **HTTP headers** (for Emby, etc.) — `spec.headers` injected via OkHttp interceptor on every request
2. **URL-path tokens** (for SMB via the local stream proxy) — the UUID token in `http://127.0.0.1:7920/stream/{uuid}` acts as the credential; no headers needed
