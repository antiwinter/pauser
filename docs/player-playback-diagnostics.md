# Player playback diagnostics: bandwidth tracker, seek throughput, MediaSource build flow

Investigation date: 2026-06-20. Branch `cat2`. Device: Android TV `192.168.17.56` (MiTV).
Test content: 菜单 (2022) on Emby endpoint "夏天" — item `207084`, a direct-play HEVC(10-bit)/DTS `.mkv`
served from `cf.maodu.xyz` (Cloudflare-fronted). PGS (bitmap) English subtitle present.

Instrumentation added for this work (kept in tree):
- `PlaybackSurface.kt` — the 1 Hz poll logs a per-second timeline under tag **`OT_BW`**:
  `mbps deltaKB totalMB pos buffered state`. State: 1=IDLE 2=BUFFERING 3=READY 4=ENDED.
- `SubtitleManager.kt` — `prepareWithSidecar` logs a rebuild marker.
- See `.claude/skills/insomnia-debug.md` → "Bandwidth / throughput diagnosis" for the capture
  recipe and remote-key driving table (seek = DPAD 21/22, menu = 82, select = 23).

---

## 1. Bandwidth tracker reads 0 after selecting a (sidecar) subtitle — FIXED

### Symptom
After selecting a subtitle the on-screen speed readout sticks at 0 forever, even though the video
keeps playing.

### Root cause
`BandwidthTracker` counts bytes via an OkHttp interceptor attached to the player's data-source
client. The normal build path (`PlaybackSpec.toMediaSource`, `PlaybackSpecExt.kt:24`) adds
`BandwidthTracker.interceptor`. But selecting an **external/bitmap** subtitle routes through
`SubtitleManager.prepareWithSidecar`, which built its **own** `OkHttpDataSource.Factory` from
`spec.httpClient.newBuilder()` **without** the interceptor. From that point every video byte flows
through an untracked client → `totalBytes` frozen → `mbps` = 0.

Emby marks a subtitle external (sidecar) when `IsExternal`, or when it is a **bitmap codec**
(PGS/VobSub/etc.) that gets converted to `.ass` server-side — see
`providers-ts/providers/emby/client.ts` `buildSubtitleTracks`. The 菜单 PGS track hits this path.

### Empirical confirmation
```
22:16:39 OT_Subtitle: select: FromSpec external trackId=6
22:16:39 OT_Subtitle: prepareWithSidecar: rebuilding video source ... /Subtitles/6/Stream.ass
22:16:40 OT_BW: mbps=0.74 totalMB=267.5 ...
22:17:34 OT_BW: mbps=0.00 totalMB=267.5 pos=394866ms buffered=23551ms state=3   <- playing, bytes uncounted
```
`totalMB` frozen at 267.5 while `state=3` and `pos`/`buffered` advance ⇒ bytes flowing through an
untracked client, not a real stall.

### Fix
`prepareWithSidecar` now adds `BandwidthTracker.interceptor` to the rebuilt client. Verified: after
the fix `totalMB` climbs again and `mbps` reports real values (~2.6 Mbps) post-subtitle.

> NOTE: this is a point-fix. The proper fix is to unify all MediaSource construction so the
> interceptor can never be forgotten again — see §4.

---

## 2. Throughput collapses after a seek — mostly server-side

The bandwidth tracker reports this correctly; the slowdown is real.

Measured pattern on this server:
- **Initial load is fast:** peaks ~20–26 Mbps while filling the buffer, settles to ~9 Mbps
  (≈ the content bitrate). Buffer fills to ~16–25 s.
- **A long-lived connection is progressively throttled.** A `+15 s` seek that lands *inside* the
  forward buffer reuses the *same* HTTP connection; throughput decays 9 → 7 → 5 → 3 Mbps and keeps
  dropping **even during rebuffering** (when ExoPlayer wants data as fast as possible). Because the
  sustained rate falls below the content bitrate (~8.5 Mbps), the buffer drains and it eventually
  stalls.
- **Unbuffered/deep seeks cost a cold gap.** A large forward seek showed ~8 s of **zero throughput**
  (origin seek latency into the remote mkv) before resuming — and only to ~7 Mbps, not the 19 Mbps
  opening burst.

Interpretation: `cf.maodu.xyz` (origin behind Cloudflare) gives a fast opening burst (likely
CDN-cached file head), then rate-limits sustained per-connection delivery below the file's bitrate.
The app reports this faithfully. A user-visible "drop after seek" is the buffer draining toward a
stall, made visible sooner by consuming buffer on the seek.

Possible app-side mitigation (not implemented, bigger change): cap the length/duration of a single
range request and cycle connections to stay near the fresh-connection rate instead of decaying to
the throttle floor.

---

## 3. Backward / deep seek can hard-crash the MKV extractor → Source error → IDLE

A `-15 s` seek (always outside the forward buffer ⇒ forces a fresh deep range request) reproduced a
**hard crash** in media3's Matroska extractor:

```
E ExoPlayerImplInternal: Caused by: ArrayIndexOutOfBoundsException:
    src.length=65536 srcPos=-1976981499 dst.length=65536 dstPos=0 length=1976981499
  at DefaultEbmlReader.read (DefaultEbmlReader.java:149)
  at MatroskaExtractor.read (MatroskaExtractor.java:593)
E TrackFallback: unhandled player error: code=2001 msg=Source error  ->  player state=1 (IDLE)
```

`srcPos` is negative and `length` ≈ 2 GB ⇒ the extractor read a **garbage EBML element size**,
which happens when a backward-seek Range response returns misaligned/wrong bytes (the origin
serving the wrong offset, or an error body, on a deep range). Reproduced **both** with the sidecar
source and on a plain no-subtitle backward seek, so it is **not** subtitle-specific — it is deep/
backward range seeks on this MKV + flaky server.

`TrackFallbackEffect` only recovers decoder (video/audio renderer) errors; a `Source error` falls to
the `else` branch and is logged but not recovered, so the player stays IDLE.

Status: on a later session this server behaved (backward seek worked), supporting the
"server range-handling" theory over a deterministic app bug. Left as a known issue. If it recurs,
candidates to investigate: detecting bad/short range responses in the data source, or guarding the
extractor seek. The Emby control API stayed healthy throughout (playback-spec probe = HTTP 200 in
0.5 s) — only the video range stream failed.

---

## 4. MediaSource construction is scattered — should be one flow (confirmed)

There are **four** places that build a video `MediaSource` and call `setMediaSource`/`prepare`:

| # | Site | Builder used | Subtitle handling |
| - | ---- | ------------ | ----------------- |
| 1 | `PlaybackSession.prepare()` `PlaybackSession.kt:205` | `spec.toMediaSource()` | text track **disabled** (`:201-204`); saved sub ignored |
| 2 | `SubtitleManager.prepareWithSidecar()` `SubtitleManager.kt:76` | **own** `OkHttpDataSource.Factory` + `DefaultMediaSourceFactory` | attaches sidecar `SubtitleConfiguration` |
| 3 | `SubtitleManager` sidecar-failure recovery `SubtitleManager.kt:251` | `spec.toMediaSource()` | none |
| 4 | `DecoderFallback` video/audio fallback `DecoderFallback.kt:74,96` | `spec.toMediaSource()` | none |

`toMediaSource` (`PlaybackSpecExt.kt`) is the de-facto unified builder used by 3 of 4 sites; it
owns the HLS-vs-progressive choice, the `BandwidthTracker` interceptor, and the per-source header
interceptor. **`prepareWithSidecar` (#2) is the lone divergence**, duplicating the client/factory
construction solely to attach a sidecar subtitle. That divergence is exactly what dropped the
bandwidth interceptor (§1).

Secondary fallout of the scattering: site #4 (decoder fallback) rebuilds via the plain
`toMediaSource`, so if a decoder fallback fires **while a sidecar subtitle is active, the sidecar
subtitle is silently lost**.

### Proposed unification
Make `toMediaSource` the single entry point and teach it about an optional sidecar subtitle:

```kotlin
fun PlaybackSpec.toMediaSource(
    context: Context,
    sidecarSubtitle: MediaItem.SubtitleConfiguration? = null,
): MediaSource
```

- Build the OkHttp client (bandwidth + header interceptors) and the HLS/progressive factory in
  one place; attach `setSubtitleConfigurations(listOf(it))` when `sidecarSubtitle != null`.
- `prepareWithSidecar` collapses to: resolve the sidecar config, then
  `exo.setMediaSource(spec.toMediaSource(context, sidecarConfig)); prepare()`.
- Sites #3 and #4 can pass the currently-active sidecar config so a recovery/fallback rebuild
  preserves the subtitle.

This guarantees no rebuild path can ever forget the interceptor again and removes the duplicate
client construction.

---

## 5. Saved subtitle not applied on first launch (confirmed)

### Symptom
On entering the player, the subtitle menu shows the previously-used track as **selected**, but it is
**not actually rendering** — the user must reselect it for it to take effect.

### Root cause
- `PlaybackSession.prepare()` restores the saved id into state: `_subtitleTrackId.value =
  seed.subtitleTrackId` (`PlaybackSession.kt:185`). The menu's `isSelected` compares against
  `session.subtitleTrackIdFlow` (`SubtitleManager.kt:190,285`), so it **renders as selected**.
- But the same `prepare()` **explicitly disables the text track** and clears overrides
  (`PlaybackSession.kt:201-204`), and builds a plain source (`:205`). **Nothing applies the saved
  track.** For an embedded text track no preferred-language/override is set; for an external/bitmap
  track `prepareWithSidecar` is never invoked.
- `resolveSubtitlePreference()` (`SubtitleManager.kt:47`) exists to map a saved id →
  embedded-vs-sidecar preference, but is **dead code — never called**.

So UI state (selected) and player state (text disabled, no sub) diverge until the user reselects.

### Fix direction (ties into §4)
On initial load, resolve the saved subtitle and apply it through the **same unified builder**:
- Call `resolveSubtitlePreference(seed.subtitleTrackId, spec)` in/around `prepare()`.
- Embedded track → don't disable text; set the override / preferred text language.
- External/bitmap track → build the initial source **with** the sidecar config via
  `toMediaSource(context, sidecarConfig)` (the unified path from §4), instead of unconditionally
  disabling text.

Doing this through the unified builder fixes first-launch restore and sidecar reselect with one
code path.

---

## Open items / decisions
1. Unify MediaSource construction (§4) — recommended; subsumes the §1 point-fix and the §5 fix.
2. Apply saved subtitle on first launch (§5).
3. Optional: connection-cycling mitigation for sustained-throughput throttling (§2) — larger change.
4. MKV backward/deep-seek `Source error` (§3) — monitor; appears server-dependent.
5. The `OT_BW` per-second logging is verbose; keep while iterating, gate or remove for release.
