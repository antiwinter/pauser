# Seek-Back Latency: Why READY Takes ~2s Even on a Cache Hit

## Context

`RelayCache` (`server/.../RelayCache.kt`) is an in-RAM, session-scoped byte cache fronting the
`/relay/{token}` route. Its goal was *immediate seek back*: when the user scrubs backward, serve
the already-buffered bytes from RAM instead of re-fetching from the provider. In practice, even
when the relay serves a `206` cache-hit range on seek-back, ExoPlayer still enters `BUFFERING`
for ~2s before reaching `READY`.

This doc records the instrumentation added to localize the delay and the **measured conclusion**:
the relay cache is not the bottleneck. The ~2s is ExoPlayer's per-seek decode/render pipeline,
which fires even on a forward seek that touches no network at all.

## Proposal we ruled out (for now): mmap-backed SimpleCache

A floated fix: bring back media3 `SimpleCache` but back it with `mmap` to avoid TV NAND wear.

**Feasibility:** `SimpleCache` is structurally file-coupled (each `CacheSpan` is a file on disk),
so you cannot just swap its storage to mmap. What you'd actually build is a custom `Cache`
implementation backed by `MappedByteBuffer`s. That is real work but doable.

**On NAND wear:** mmap of a *real file* still writes back to flash via the page cache — it does
**not** spare the NAND. To actually avoid wear you need `MAP_ANONYMOUS` (pure RAM, no file) or a
file on tmpfs/ramfs. Both are functionally "RAM like RelayCache, just off-heap." So the NAND
argument only holds if you mean *anonymous* mmap — i.e. an off-heap RAM cache, not a disk cache.

**Why we ruled it out:** the logs (below) prove the cache already serves a hit in ~45ms. The 2s
happens *after* the bytes arrive, inside ExoPlayer. Rewriting the cache backend burns effort on a
component that is not the bottleneck.

## Instrumentation

Two log timelines are emitted. Player and relay run in the same process, so `System.nanoTime()`
is directly comparable across the `sd:` (player) and `sr:` (relay) logcat lines.

### Player side — `SeekDiagnostics.kt`

`player/.../engine/SeekDiagnostics.kt` exposes a single `AnalyticsListener` attached in
`PlaybackSession.init`. `PlaybackSession.seekTo` and `rebuildKeepingPosition` call
`SeekDiagnostics.markSeek(pos)` to arm a baseline; the listener then logs `+Nms since seek` at:

- `onVideoDecoderInitialized` — codec-init duration (media3 reports `initializationDurationMs`)
  and its offset from the seek.
- `onRenderedFirstFrame` — the key split point: when the decoder emits its first post-seek frame.
- `onPlaybackStateChanged` — each state transition; on `READY`, a summary line
  `sd: ready total=+Xms first-frame=+Yms codec-init=Zms` and the baseline is reset.

Non-seek state changes (initial prepare) are ignored because the baseline is zero.

### Relay side — `StreamRelayRoute.kt`

The cache-hit serve path records:

- `sr: cache hit [...] t=<nanoTime>` — when the hit is decided.
- `sr: serve begin t=<nanoTime> range=[start-end]` — entry into the writer.
- `sr: serve end t=<nanoTime> written=<bytes> firstByteAt=<nanoTime> firstByte+<ms>ms` —
  completion, plus the first-byte lag relative to the hit decision.

The remote/tee path is unchanged.

## Measured findings

Reproduced on Emby `original.mp4` (MP4). Two cases, both with `cached=true`:

### SEEK_BACKWARD — cache hit, the case we set out to fix

Timeline aligned to `sd: seek` = t0:

| +ms   | event                                                         | side   |
|-------|---------------------------------------------------------------|--------|
| +0    | `sd: seek pos=1352782ms`                                      | player |
| +99   | `sd: state=BUFFERING`                                         | player |
| +208  | `BW request ... range=bytes=814082963-` (OkHttp fires)        | player |
| +217  | `sr: req range=776.4- cached=true`                            | relay  |
| +218  | `sr: cache hit [776.4, 796.7] length=20.3`                    | relay  |
| +224  | `sr: serve begin`                                             | relay  |
| +255  | first byte to socket (`firstByte+37ms`)                       | relay  |
| +318  | `diag: ahead=0ms` (buffer still empty)                        | player |
| +1322 | `diag: ahead=6490ms` (6.5s of media loaded)                   | player |
| +1895 | `sd: first-frame`                                             | player |
| +2045 | `sd: READY` (`codec-init=-1`)                                 | player |
| +21374| `sr: serve end written=20.3` (player sipped it over 21s)     | relay  |

The relay served the hit in **~45ms** (request → first byte). The cache delivered the full 20.3
MiB; the player read it slowly over 21s for sustained playback, i.e. supply was never the
constraint. The **~1790ms from first-byte to first-frame is entirely inside ExoPlayer.**
`codec-init=-1` → the decoder was *not* reinitialized, so this is not codec cold-start.

Note: ExoPlayer took **208ms** just to go from `seekTo` to issuing the HTTP request — that's the
extractor computing the sync-sample byte offset and opening the OkHttp connection, before the
relay is even contacted.

### SEEK_FORWARD — the smoking gun

Forward seek within already-buffered data (`ahead=35085ms` at the moment of seek). **No `sr: req`
fires at all** — ExoPlayer never touched the network. Yet:

- +1338ms `sd: first-frame`
- +1514ms `sd: READY`
- `codec-init=-1` (no reinit)

So with 35s of media already in RAM and zero network involvement, a seek still costs ~1.5s. That
is the floor of ExoPlayer's seek machinery on this source: decoder **flush** + decode-from-
nearest-keyframe + rebuffer gate + first-frame render. No relay-side change can get below it.

## Where the ~2s actually goes

Resolving the earlier candidate list against the logs:

1. **Relay supply latency — ruled out.** `firstByte+37ms`; the full 20.3 MiB served from RAM. The
   relay is ~45ms of the 2045ms total. Not the bottleneck.
2. **Container demux / `moov` at end — not observed here.** ExoPlayer issued exactly one range
   request for the seek target (no separate moov fetch), so the moov was already parsed/cached
   from initial load. The source is plain MP4, not HLS/TS. #2 does not contribute on this stream.
3. **Codec (re)init — ruled out.** `codec-init=-1` in both cases; the decoder was reused, not
   recreated. The flush that *does* happen on every seek is not reported by
   `onVideoDecoderInitialized`, which is why the callback is silent.
4. **Keyframe snap + decode-forward — the dominant remaining cost.** This is what survives. On a
   seek, ExoPlayer flushes the decoder, snaps to the previous sync sample, and decodes forward to
   the target frame, discarding the work. With a multi-second GOP (typical for Emby
   `original.mp4`) this is ~1.5–1.8s and stacks with the rebuffer gate. The forward-seek log
   (1.5s, no network) isolates this cost cleanly.

## Levers that could actually move it

- **`bufferForPlaybackAfterRebufferMs`** (currently `1_000` in `InsomniaExoPlayer`). Lowering it
  shaves the gate, but the gate is one component — forward-seek (buffer already full) is still
  1.5s, so the ceiling is ~1.3s. Diminishing returns.
- **ExoPlayer back-buffer.** Keep decoded frames behind the playhead so a backward seek reuses
  them instead of flushing + re-decoding from the keyframe. This is the only lever that targets
  the dominant cost (#4). A player-side `MediaCodecVideoRenderer` / `DefaultLoadControl` change,
  not a relay change.
- **Shorter GOP** — not controllable; it is the source file.

## Conclusion

The cache was the right idea for avoiding a remote round-trip, and it works — seek-back hits the
cache in ~45ms. But it was the wrong hole to plug for *immediate* seek-back: the 2s is not supply
latency, it is ExoPlayer's seek-to-render pipeline, and it applies even to forward seeks within
fully-buffered data. Further work belongs on the player side (back-buffer / decode reuse), not on
`RelayCache`.
