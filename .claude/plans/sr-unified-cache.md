# Plan: unified sr relay + session RAM cache

## Goal

Route ALL player traffic through the local relay (`sr`) and put one session-only,
size-limited **in-RAM byte cache** inside sr. ExoPlayer drops its disk cache
(`PlayerCache` / `CacheDataSource`) and shrinks its ahead buffer 5 min → 1 min.

Target pipeline:

```
file service (SMB/…)  → sr [RAM cache, readAt pump]        → player [pos, 1min buffer]
remote HTTP (prog.)   → sr [RAM cache, proxyClient fetch]  → player [pos, 1min buffer]
HLS                   ────────── direct (proxy at player) ─→ player [pos, 1min buffer]
JS spider InputStream → sr [pass-through, NO cache]        → player
```

Why: on seek-back ExoPlayer drops consumed encoded samples and re-loads from the
DataSource. With the back-buffer now in sr's RAM cache, the seek hits cache
(loopback, instant) instead of re-fetching over LAN/proxy. The player only holds
1 min ahead; sr holds the trailing window.

## Design decisions (confirmed in discussion)

1. **Cache lives in sr, not ExoPlayer.** Session-only, in RAM, process-lifetime.
   No `SimpleCache`, no SQLite index, no NAND writes. This is the point of the
   pivot away from the media3 disk cache.
2. **Cacheable recipes: `fs` + `url` only.** JS spider is **not** cacheable —
   its `InputStream` is opaque/sequential (verified against fongmi-tv
   `Proxy.java`: `newChunkedResponse` from byte 0, no Range/206). It stays a
   plain pass-through pump under `/relay/{token}`.
3. **HLS stays direct.** Wrapping an HLS manifest URL in `/relay/url?url=…`
   breaks relative segment-URL resolution against the manifest URL. HLS keeps
   using the player's proxy-aware `PlaybackSpec.httpClient`.
4. **Proxy moves into sr.** Desired flow is `player → sr → proxyClient →
   originalUrl`. The player's okhttp for sr-routed sources becomes a **plain
   no-proxy loopback client** — this is mandatory: `HttpProxyClient` builds a
   hard-coded `Proxy(Proxy.Type.HTTP, InetSocketAddress(host,port))` with no
   localhost bypass, so a proxied player okhttp would route `127.0.0.1:7920`
   through the remote proxy and break. sr's `/relay/url` resolves
   `ep → EndpointClient.proxyClient.getHttpClient()` at fetch time so the proxy
   is applied on the sr→origin leg, not the player→sr leg.
5. **Eviction: global LRU by access time.** ~600 spans at 256 KiB for 150 MB —
   a linear/`TreeSet`-by-lastAccessed scan is cheap. Sequential playback
   touches spans in order, so the oldest (farthest behind pos) evict first →
   the cache naturally holds a trailing window of recently-read bytes. On
   seek-back, those spans are still cached. No need for position-aware
   eviction; LRU gives the sliding window for free.
6. **RAM budget.** 150 MB, not 500 MB. Android TV per-process heap is 256–512
   MB shared with Compose/decoder; 500 MB in-RAM is not feasible. 150 MB ≈
   ~4 min trailing window at 5 Mbps — the seek-back window is bounded by
   cache size ÷ bitrate. Make it `min(150MB, 30% of Runtime.maxMemory())` so
   low-RAM devices don't OOM.
7. **v1 cache: full-range hit-or-miss, fetch-on-miss fills chunks.** On a
   Range request, if the entire requested range is cached → serve from RAM.
   Else fetch the whole range from upstream, relay to the player **and** cache
   in 256 KiB chunks as bytes flow. Partial hits are treated as misses
   (re-fetch the overlap) — rare (only at the cache-window edge on seek-back),
   minor waste, far less code than gap-filling. Gap-filling is a v2 follow-up.

## Changes

### 1. `RelayCache` — session RAM byte cache (new, `:content:contract`)

**`content/contract/.../RelayCache.kt`** — singleton, same module as `FileRelay`
/ `StreamRelayRegistry` so both cacheable recipes can reach it.

```kotlin
object RelayCache {
    private const val CHUNK = 256 * 1024
    private val maxBytes = min(150L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 3)
    private val entries = ConcurrentHashMap<String, CacheEntry>()  // key -> entry
    private val totalBytes = AtomicLong(0)
    // global LRU: a mutex-guarded LinkedHashMap<String, Span> (access-order=true)
    //   OR a TreeSet<Span> by lastAccessed. Pick one in implementation.

    // Serve [start,end) fully from cache into `sink`. Returns true only if the
    // whole range was a hit; false => caller fetches from upstream.
    suspend fun tryServe(key: String, start: Long, end: Long, sink: ByteSink): Boolean

    // Cache a contiguous run of bytes (called from the fetch pump as upstream
    // bytes flow). Splits into CHUNK-sized spans. Evicts globally when over
    // maxBytes. No-op if the entry was evicted concurrently (don't resurrect).
    suspend fun put(key: String, start: Long, data: ByteArray, len: Int)

    fun touch(key: String)               // heartbeat refresh (optional)
    fun evict(key: String)               // drop a whole entry on STOPPED
}
```

- `CacheEntry`: `TreeMap<Long, Span>` (start → span) for range lookup, per-entry
  `Mutex` so a concurrent `put`+`tryServe` on the same key doesn't corrupt.
- `Span(start, length, ByteArray, lastAccessed)`.
- Eviction: while `totalBytes > maxBytes`, remove the globally-least-recently-
  accessed span (across all keys), `totalBytes -= span.length`. Evict on the
  `put` path only.
- **No persistence.** Object dies with the process. Cleared implicitly.

### 2. `UrlRelayRecipe` — proxy-aware remote-URL relay with caching (new)

**`content/contract/.../UrlRelayRecipe.kt`** — the new recipe behind `/relay/url`.

```kotlin
class UrlRelayRecipe(
    private val resolveClient: suspend (endpointId: String) -> EndpointClient?,
) : StreamRelayRecipe {
    override suspend fun serve(params: Map<String, String>): StreamRelayResult? {
        val ep = params["ep"] ?: return null
        val originalUrl = params["url"] ?: return null
        val rangeHeader = params["Range"]
        val client = resolveClient(ep) ?: return null
        // totalSize unknown until first fetch; for Range we rely on upstream 206.
        return StreamRelayResult(
            status = if (rangeHeader != null) 206 else 200,
            contentType = null,   // forwarded from upstream below
            headers = buildMap {
                put("Accept-Ranges", "bytes")
                // Content-Range/length filled from the upstream 206 response
            },
            length = /* upstream Content-Length */,
            pump = { sink -> pumpUrl(client, originalUrl, start, length, sink) },
        )
    }
    companion object {
        fun ensureRegistered() { /* DCL register under "url", like FileRelayRecipe */ }
    }
}
```

- `pumpUrl`: builds an upstream `Request` with the player's `Range` header using
  `client.proxyClient.getHttpClient()` (proxy-aware). On `Response`:
  - forward `Content-Type` / `Content-Range` / `Content-Length` to the player
    (the recipe sets these on the `StreamRelayResult` before pumping — so
    `serve` must do a HEAD/probe first, OR the route supports deferred headers.
    **Simplest:** issue the upstream GET with Range, read its status/headers,
    *then* build the `StreamRelayResult` from them, and pump the `ResponseBody`
    chunk-by-chunk: each chunk → `RelayCache.put(key, pos, chunk)` AND
    `sink.write(chunk)`. Cache key = `"url:$originalUrl"`.)
  - On cache hit: skip the upstream call, `RelayCache.tryServe(key, start, end,
    sink)`.
- **Key insight:** the upstream fetch and the cache-fill happen in the *same*
  pump loop — no double read. `tryServe` is checked first (hit → pure cache
  pump, no upstream connection); on miss the upstream pump writes to both sink
  and cache.

**Note on `serve` returning headers from upstream:** the current
`StreamRelayResult` is built before pumping. To forward upstream `Content-Type`
etc., `serve` must open the upstream `Response` synchronously (suspend), read
its headers, then return a `pump` that streams the body. Close the response in
the pump's `finally`. This is the same shape as the JS recipe's InputStream
bridge — the blocking `ResponseBody.byteStream()` read goes inside
`withContext(Dispatchers.IO)`.

### 3. Wire `/relay/url` route — no route change needed

`StreamRelayRoute` already dispatches `/relay/{token}` to the registered recipe.
Registering `UrlRelayRecipe` under token `"url"` makes `/relay/url?ep=…&url=…`
work with **zero route code**. `serve` receives `ep`/`url`/`Range` in `params`
(the route already passes query params + `Range` header into the params map —
confirm in `StreamRelayRoute.kt`; if not, extend the params builder to include
`Range`).

### 4. `FileRelayRecipe` — add caching to the existing `fs` recipe

**`content/contract/.../FileRelay.kt`** — `serve` gains a cache check before
the `withStream` pump:

- Cache key = `"fs:$ep:$ref"`.
- On `serve`: compute `start`/`end`/`length` as today. If
  `RelayCache.tryServe(key, start, end, sink)` returns true → return a pump
  that just does that (no `withStream`, no `readAt`).
- Else → return the existing `withStream`/`readAt` pump, but `pumpProviderStream`
  writes each 128 KiB chunk to **both** `sink` and `RelayCache.put(key, pos,
  chunk, read)`.

The 128 KiB SMB pump chunk is finer than the cache's 256 KiB — fine, `put`
chunks at whatever size it's given; the `TreeMap` spans will just be 128 KiB
for `fs`. Don't force alignment.

### 5. epcache — wrap remote HTTP URLs, pick the right player httpClient

**`content/contract/.../epcache/spec.kt`** + **`wrapper.kt`**:

- In `enrichSpec` / wherever `PlaybackSource`s are built for non-file-service
  endpoints (Emby HTTP, spider JS results that return a plain HTTP URL — **not**
  the spider's own `proxy()` which goes through `/relay/{token}`):
  - If `src.mimeType` is HLS (`application/vnd.apple.mpegurl` /
    `application/x-mpegURL`) → **leave URL direct**, tag the source as
    `directProxy = true`.
  - Else (progressive HTTP) → rewrite `src.url` to
    `relayUrl(ep, src.url)` where:
    ```kotlin
    fun relayUrl(endpointId: String, originalUrl: String): String =
        "http://127.0.0.1:${SERVER_PORT}/relay/url?ep=" +
            Uri.encode(endpointId) + "&url=" + Uri.encode(originalUrl)
    ```
    (`Uri.encode` — same `%20`-not-`+` rule as `streamUrl`, see `spec.kt:26-31`.)
    Call `UrlRelayRecipe.ensureRegistered()` before emitting such URLs.
- `PlaybackSpec.httpClient`: for sr-routed sources (fs + url), pass a **plain
  `OkHttpClient()`** (no proxy — loopback only). For HLS/direct sources, pass
  `delegate.proxyClient.getHttpClient()` (proxy-aware). Since `PlaybackSpec`
  carries one httpClient for all sources, pick per-source at build time: if any
  source is HLS, use the proxy client (HLS needs it); if all sources are
  sr-routed, use the plain client. (Mixed is rare; document the rule.)

### 6. Player — drop disk cache, shrink buffer

- **Delete** `player/.../engine/PlayerCache.kt`.
- **`player/.../engine/PlaybackSpecExt.kt`**: remove the `CacheDataSource.Factory`
  wrapping; build the media source directly on `OkHttpDataSource.Factory(okHttp)`.
- **`OpenTuneExoPlayer.kt`** (or wherever `DefaultLoadControl` is built):
  `setMaxVideoBufferMs` / `maxBufferMs` 5 min → 1 min. Keep `minBufferMs` as-is.
  (Less RAM pressure since sr now holds the back-buffer.)
- **Revert the partial `media3-database` additions:**
  - `gradle/libs.versions.toml:48` — delete the `media3-database = { … }` line.
  - `player/build.gradle.kts:34` — delete `implementation(libs.media3.database)`.
  (These were staged for the 3-arg `SimpleCache` fix that this plan obsoletes.)
- The `@Suppress("DEPRECATION")` goes away with `PlayerCache.kt`.

### 7. Liveness / heartbeat (unchanged shape)

`FileRelay.touch`/`evict` already fire from the heartbeat (`wrapper.kt`).
Extend the same hook to `RelayCache.touch(key)` / `RelayCache.evict(key)` so a
stopped playback drops its cache entry (frees RAM for the next item). The
`activeStreamRefs` map already tracks video+subtitle refs; for `url` sources,
track `originalUrl` instead. Keep one eviction path.

## Risks / edge cases

- **Proxy localhost bypass — NONE.** Confirmed: `HttpProxyClient` uses a
  hard-coded `Proxy(HTTP, InetSocketAddress(host,port))`. The player's okhttp
  for sr URLs **must** be plain (no proxy), or loopback gets misrouted through
  the remote proxy. sr's `/relay/url` fetch applies the proxy on the sr→origin
  leg. This is the single most important correctness invariant.
- **BandwidthTracker inflation on cache hits.** `BandwidthTracker` is an okhttp
  interceptor on the player's okhttp. sr cache hits are served over loopback →
  the tracker sees loopback mbps (high) on seek-back, not the real LAN rate.
  Accept: the tracker already only sees HTTP-fetched bytes; seek-back genuinely
  uses no network, so a high number is "correct" (no bandwidth spent). Note in
  InfoOverlay if misleading.
- **Double-buffered 1 min ahead.** The 1-min ahead buffer exists both as raw
  bytes in sr's cache (pre-decode) and as encoded samples in ExoPlayer
  (~37 MB at 5 Mbps). Accepted — the cost is small and the simplification large.
- **Reduced stall resilience.** With 1 min (not 5 min) ahead, a flaky remote
  link stalls the player sooner. sr's cache doesn't help ahead-stalls (it's a
  back-buffer). Accept; if it bites, raise `minBufferMs`, not the cache.
- **Concurrent `tryServe` + `put` on one key** — per-entry `Mutex` serializes;
  a miss inflight could race a second request for the same range. v1: the
  second request also misses and fetches (duplicate upstream fetch). Acceptable
  (ExoPlayer's progressive loader is single-threaded; HLS is direct, not here).
- **`serve` opening upstream synchronously** — the `UrlRelayRecipe.serve` does
  a suspend upstream GET to read headers before returning the pump. This blocks
  the route's `respond` until headers arrive. Bounded by upstream RTT; the
  existing JS recipe has the same shape. If a slow origin delays the response
  header, the player sees a slow TTFB — same as today's direct fetch.
- **No upstream Range support** — some servers ignore `Range` and return 200
  from byte 0. On such a miss, sr cannot cache a partial range meaningfully
  (the response is the whole file). Detect `200` (not `206`) on the upstream:
  pass through without caching (treat like the JS spider case). Only cache when
  upstream honors Range (206 + `Content-Range`).

## Verification

1. `./gradlew :content:contract:compileDebugKotlin :server:compileDebugKotlin :player:compileDebugKotlin :app:compileDebugKotlin` — green; `media3-database` no longer referenced.
2. **SMB playback** (`fs`): plays end-to-end; first load fetches over LAN (real mbps in InfoOverlay); seek-back to a played position is instant, no `readAt` in logs (cache hit).
3. **Progressive HTTP** (`url`, with a proxy configured): plays end-to-end through `player → sr → proxyClient → origin`. Confirm via logs that the proxy is on the sr→origin leg (proxy host appears in sr fetch, not player fetch). Seek-back hits cache.
4. **HLS direct**: plays; proxy applied at the player; **not** routed through `/relay/url`. Segments load normally.
5. **JS spider** (`/relay/{token}`): unchanged, pass-through, **not cached**. Seek re-runs the spider (as today).
6. **No `media3-database` reference** — `grep -r media3-database gradle/ player/` returns nothing.
7. **RAM** — play a long file, confirm `RelayCache` stays ≤ `maxBytes` via a debug counter; seek-back window ≈ cache÷bitrate.
8. **Stop** — on STOPPED, the entry's cache spans are evicted (RAM freed for the next item).
