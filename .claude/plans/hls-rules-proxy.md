# Plan: HLS Rules Proxy

## Goal

Apply CatVod `rules` filters to HLS manifests before ExoPlayer sees them,
stripping ad segments matched by per-host regex patterns.

## Rules format (from config)

```json
{
  "name": "cdn.ryplay",
  "hosts": ["cdn.ryplay"],
  "regex": [
    "#EXT-X-DISCONTINUITY\\r*\\n*#EXTINF:5.480000,[\\s\\S]*?#EXT-X-DISCONTINUITY",
    "..."
  ]
}
```

Each rule has a list of hostnames it applies to and a list of regex patterns.
Each pattern is applied as a global replace-with-empty against the raw `.m3u8` text.
Rules are matched by hostname of the `.m3u8` URL.

## Architecture

### Where the proxy lives

Add an `HlsProxy` route module to the existing Ktor server (`OpenTuneServer`),
parallel to `StreamProxy`. It binds at `/hls/{token}/{path...}`.

```
ExoPlayer → GET http://127.0.0.1:7920/hls/{token}/playlist.m3u8
                                    ↓
                            HlsProxy.kt
                    fetch real URL, apply rules, return cleaned m3u8
                    (segment .ts URLs pass through unmodified)
```

### Token lifecycle

- TS calls `host.hls.wrap({ url, rules })` → Kotlin registers a token, returns a proxy URL
- `getPlaybackSpec` wraps the playback URL with this call when `config.rules` is non-empty
  and the URL hostname matches any rule
- Token is revoked when the provider's `onStop` hook fires (reuse existing hook flow)

### Data flow for `.m3u8` requests

1. ExoPlayer requests `http://127.0.0.1:7920/hls/{token}/playlist.m3u8`
2. `HlsProxy` resolves token → original base URL + rules
3. Fetches the real `.m3u8` from origin (OkHttp, pass through original request headers)
4. Applies matching rules: for each regex in the rule set, `Regex(pattern, MULTILINE or DOT_MATCHES_ALL).replace(manifest, "")`
5. Rewrites relative segment URLs in the manifest to absolute (so ExoPlayer can fetch them directly from origin — no need to proxy `.ts` segments)
6. Returns cleaned manifest as `application/vnd.apple.mpegurl`

Segment (`.ts`) requests go directly to origin — only manifests are proxied.
Chunked/live manifests (with `EXT-X-STREAM-INF` / no `EXT-X-ENDLIST`) need the same
treatment on each poll; the proxy is stateless per-request so this works naturally.

### Nested manifests (master → media playlist)

Master playlists (`#EXT-X-STREAM-INF`) contain URLs to variant/media playlists
which also need proxying (they may contain ad segments). Rewrite variant playlist
URLs in the master to also go through the proxy (`/hls/{token}/...`) rather than
returning them as absolute origin URLs.

## Implementation steps

### 1. TS side — `host.hls` API

Add to `HostAPI` in `utils/types.ts`:
```ts
hls: {
  wrap(args: { url: string; rules: HlsRule[] }): Promise<string>;
  revoke(args: { url: string }): Promise<void>;
}
```

Add `HlsRule` type to `config.ts` (already has `rules?` field to add):
```ts
export interface HlsRule {
  name:   string;
  hosts:  string[];
  regex:  string[];
}
```

Update `CatVodConfig` to include `rules?: HlsRule[]`.

### 2. TS side — wrap URL in `getPlaybackSpec`

In `instance.ts`, after resolving the playback spec, check if `config.rules` has
any rule whose `hosts` matches the playback URL hostname. If so:

```ts
const matchedRules = rulesForUrl(spec.url, state.config.rules);
if (matchedRules.length > 0) {
  spec.url = await host.hls.wrap({ url: spec.url, rules: matchedRules });
}
```

Extract `rulesForUrl` as a pure helper (hostname extraction + filter).

### 3. Kotlin — `HlsRule` data class + `HlsProxy`

**`HlsRule.kt`** (in `:server` module):
```kotlin
data class HlsRule(val hosts: List<String>, val patterns: List<Regex>)
```

**`HlsProxy.kt`**:
- `wrap(originalUrl, rules): String` — stores token → `(originalUrl, rules)`, returns proxy URL
- `revoke(proxyUrl)` — removes token
- Ktor route: `get("/hls/{token}/{path...}")` 
  - Reconstructs real URL from `originalUrl` base + `{path...}` + query params
  - Fetches via OkHttp with original headers forwarded
  - If response is `application/vnd.apple.mpegurl` or `.m3u8`: apply rules + rewrite URLs, return cleaned text
  - Otherwise: stream bytes through unchanged (shouldn't happen, but safe fallback)

**URL rewriting in manifest**:
- Relative URLs → prepend real origin base
- Variant playlist URLs → rewrite to `/hls/{token}/...` (so they also get proxied)
- Segment `.ts` URLs → rewrite to absolute origin URLs (bypass proxy)

### 4. Kotlin — wire into `HostApis` + `QuickJsEngine`

Add `"hls"` namespace to `dispatchHost` in `QuickJsEngine.kt`, delegating to
a new `HostApis.handleHls(name, argsJson, hlsProxy)`.

Add `HlsProxy` instance to `QuickJsEngine` constructor (alongside `JarLoader`).

### 5. Kotlin — install route in `OpenTuneServer`

```kotlin
with(hlsProxy) { installRoutes() }
```

`HlsProxy` follows the same `Application.installRoutes()` extension pattern as `StreamProxy`.

## Key decisions / constraints

- **Only manifest URLs are proxied** — `.ts` segment URLs are rewritten to absolute
  origin so ExoPlayer fetches them directly. Avoids proxying potentially large video chunks.
- **Regex flags** — rules use `MULTILINE` + `DOT_MATCHES_ALL` to match the
  multi-line patterns in the format (e.g. `[\\s\\S]*?`). Test each pattern with
  `runCatching` and skip invalid ones.
- **No token expiry** — tokens are revoked explicitly via `host.hls.revoke` on stop,
  matching the existing `StreamProxy` pattern.
- **Live/chunked manifests** — work naturally since each ExoPlayer poll is a fresh
  request through the proxy.
- **`hosts` field in `HlsProxy`** is different from `config.hosts` (DNS remapping) —
  these are rule-matching host patterns, not URL rewrites. Apply `config.hosts` remapping
  first, then check rules against the remapped URL.
