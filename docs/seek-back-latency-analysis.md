# Seek-Back Latency: Why READY Takes 2–3s Even on a Cache Hit

## Context

`RelayCache` (`server/.../RelayCache.kt`) is an in-RAM, session-scoped byte cache fronting the
`/relay/{token}` route. Its goal was *immediate seek back*: when the user scrubs backward, serve
the already-buffered bytes from RAM instead of re-fetching from the provider. In practice, even
when the relay serves a `206` cache-hit range on seek-back, ExoPlayer still enters `BUFFERING`
for ~2–3s before reaching `READY`.

This doc captures the investigation, rules out the cache backend as the cause, and instruments the
pipeline to localize the real delay.

## Proposal we ruled out (for now): mmap-backed SimpleCache

A floated fix: bring back media3 `SimpleCache` but back it with `mmap` to avoid TV NAND wear.

**Feasibility:** `SimpleCache` is structurally file-coupled (each `CacheSpan` is a file on disk),
so you cannot just swap its storage to mmap. What you'd actually build is a custom `Cache`
implementation backed by `MappedByteBuffer`s. That is real work but doable.

**On NAND wear:** mmap of a *real file* still writes back to flash via the page cache — it does
**not** spare the NAND. To actually avoid wear you need `MAP_ANONYMOUS` (pure RAM, no file) or a
file on tmpfs/ramfs. Both are functionally "RAM like RelayCache, just off-heap." So the NAND
argument only holds if you mean *anonymous* mmap — i.e. an off-heap RAM cache, not a disk cache.

**Why we ruled it out for now:** switching cache backends does not address the observed symptom.
The cache already serves instantly on a hit; the 2–3s happens *after* the bytes arrive. Rewriting
the cache would burn effort on a component that is not the bottleneck.

## The real question: where does the 2–3s go?

`READY` wall-clock = **time-to-first-decoded-sample** + **time-to-fill-buffer**.

If decoding runs at 5–10× realtime, filling a 2500ms buffer should take ~250–500ms — not 2.5s.
`DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS` (2500ms) is therefore at most a few hundred
ms of the gap. It cannot be the whole story. The bulk of the 2–3s is *before the decoder produces
anything*, which means the cache backend is the wrong place to look.

Candidate contributors, roughly in order of likelihood on a TV SoC:

1. **Relay supply latency, even on a "hit."** `RelayCache.tryServe` copies byte ranges under its
   mutex into a slice list, then writes them to the socket. For a multi-megabyte range that is a
   lot of `copyOfRange` + GC pressure. If the relay process is in stop-the-world GC during the
   serve, the `206` is "served" but trickles. The earlier monitoring confirmed a `206` was served
   but did **not** measure first-byte / last-byte timing.

2. **Container demux, especially MP4 with `moov` at end.** If the stream is MP4 and `moov` is not
   at the front, ExoPlayer issues a *separate* range request to the end of the file to read
   `moov`, parses it, then comes back to request the seek range — two serialized round-trips
   through the relay before the first sample reaches the demuxer. HLS/TS and fragmented MP4
   avoid this.

3. **Codec (re)init on the TV's SoC.** After a seek that flushes, `MediaCodec` may be flushed and
   sometimes recreated. On low-end TV chipsets codec init is routinely 200–800ms, occasionally
   over 1s; the first output frame also lags by several input frames after a flush.

4. **Keyframe snap + decode-forward.** Default `SeekParameters.CLOSEST_SYNC` snaps back to the
   previous keyframe and decodes forward, discarding until the target. Sparse keyframes
   (every 4–10s) × ~5× decode = 0.5–1.5s of throwaway work. Not 2–3s alone, but it stacks with #3.

## Instrumentation added

To split the gap cleanly, two log timelines are now emitted. Player and relay run in the same
process, so `System.nanoTime()` is directly comparable across the `sd:` (player) and `sr:`
(relay) logcat lines.

### Player side — `SeekDiagnostics.kt` (new)

`player/.../engine/SeekDiagnostics.kt` exposes a single `AnalyticsListener` attached in
`PlaybackSession.init`. `PlaybackSession.seekTo` and `rebuildKeepingPosition` call
`SeekDiagnostics.markSeek(pos)` to arm a baseline; the listener then logs `+Nms since seek` at:

- `onVideoDecoderInitialized` — codec-init duration (media3 reports `initializationDurationMs`)
  and its offset from the seek.
- `onRenderedFirstFrame` — the key split point: when the decoder emits its first post-seek frame.
- `onPlaybackStateChanged` — each state transition; on `READY`, a summary line
  `sd: ready total=+Xms first-frame=+Yms codec-init=Zms` and the baseline is reset.

Non-seek state changes (initial prepare) are ignored because the baseline is zero.

### Relay side — `StreamRelayRoute.kt` (extended)

The cache-hit serve path now records:

- `sr: cache hit [...] t=<nanoTime>` — when the hit is decided.
- `sr: serve begin t=<nanoTime> range=[start-end]` — entry into the writer.
- `sr: serve end t=<nanoTime> written=<bytes> firstByteAt=<nanoTime> firstByte+<ms>ms` —
  completion, plus the first-byte lag relative to the hit decision.

The remote/tee path is unchanged.

## How to read the logs

Reproduce a seek-back that lands on a cache hit, then filter `logcat` for `sd:` and `sr:`. The
timeline (all in ms, same nanoTime basis) is:

| Phase | Delta | Source line |
|-------|-------|-------------|
| seek armed | t0 | `sd: seek ... t=…` |
| relay receives req | T1 − t0 | `sr: req range=…` |
| cache hit decided | T_hit − t0 | `sr: cache hit ... t=…` |
| first byte to socket | T_fb − T_hit | `sr: serve end ... firstByte+…ms` |
| serve complete | T_end − t0 | `sr: serve end t=…` |
| decoder init done | codec-init | `sd: decoder-init ... dur=…ms` |
| first frame rendered | T_ff − t0 | `sd: first-frame +…ms since seek` |
| READY | T_ready − t0 | `sd: ready total=+…ms` |

Interpretation:

- If `first-frame` lands at ~2.5s and `ready` at ~2.7s → the bottleneck is **upstream of the
  decoder** (supply / demux / codec-init). Cache rewrite will not help; chase #1–#3 above.
- If `first-frame` is at ~300ms and `ready` at ~2.6s → it **is** the buffer threshold; tune
  `DefaultLoadControl` (`InsomniaExoPlayer.createForBundledSources`). This contradicts fast
  decode, so it is the less likely outcome.
- `codec-init` > 500ms points squarely at TV `MediaCodec` recreation (#3).
- `firstByte+…ms` (relay) in the hundreds-of-ms range points at GC / mutex contention on
  `RelayCache.tryServe` (#1).

## Open questions to answer from the logs

- What container is the relay serving (MP4 with `moov` at end? HLS? TS)? Determines whether #2
  applies.
- Is the relay process showing GC churn during seeks? Confirms/refutes #1.
- Does `firstByte+…ms` on the relay stay low while `first-frame` stays high? If so, the delay is
  on the player side, not the relay.

Once the logs localize the gap, the fix is targeted (e.g. moov-relocation, codec reuse,
`LoadControl` tuning, or off-heap cache only if GC is the proven cause).
