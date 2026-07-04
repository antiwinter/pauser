# Bug Analysis: Pause/Resume Buffering Issues

**Status:** Analysis in progress, suspended for future fix  
**Date:** 2026-07-02  
**Analyzed by:** Claude (Kiro AI assistant)

---

## Bug Descriptions

### Bug 1: Fast download but gray bar not refilling after pause/resume

**Trigger:** `leaveSurface()` = enter detail screen = `session.pause()`

**Symptoms:**
- Pre-buffer is quickly consumed after resuming from a long pause
- Player enters buffering state
- Download speed is fast (high mbps visible in BandwidthTracker/InfoOverlay)
- Light-gray buffered bar in PlaybackControllerBar does NOT increase
- Player is requesting/downloading something, but the buffer doesn't refill

### Bug 2: Slow download speed after stop/resume

**Trigger:** Back to browse/home/exit app = `session.stop()`

**Symptoms:**
- After exiting player and resuming playback, download speed becomes slow
- Observation: "every time I open a new movie on a new day, the speed is fast no matter how long I watch it"
- But if you pause or stop watching for a few minutes, when you resume it goes bad
- Even exiting the app doesn't fix the speed

---

## Key Facts Confirmed

### Playback Method
- **Direct Play** (`static=true` URLs), NOT transcoding
- This means: plain HTTP byte-range static file serving, no server-side ffmpeg transcode jobs
- No server-side throttling involved
- All my initial analysis about Jellyfin `TranscodingThrottler` and `DownloadPositionTicks` is **NOT applicable** to this case

### Player Configuration
- ExoPlayer with custom `DefaultLoadControl`:
  - `minBufferMs = 290,000ms` (4:50)
  - `maxBufferMs = 300,000ms` (5:00)
  - Pre-buffer target: 5 minutes
- BandwidthTracker: OkHttp interceptor tracking byte reads
- Progress heartbeat: 10 seconds (`DEFAULT_PROGRESS_INTERVAL_MS`)

### Lifecycle Flow

**Bug 1 scenario (leaveSurface):**
1. User playing → enters detail screen (player surface hidden)
2. `TvPlayerSurface.leaveSurface()` (PlaybackSurface.kt:31) → `session.pause()` (PlaybackSession.kt:209)
3. `session.pause()` → `exo.playWhenReady = false` + `syncEntryState(PAUSED)` (saves position to storage)
4. ExoPlayer pauses, stops downloading, **but keeps buffered data in memory**
5. User clicks Resume button on detail screen
6. `resumePlay()` (MovieDetailScreen.kt:41) → `playerController.play()` → `playbackSession.play()`
7. **No `prepare()` call**, just `exo.playWhenReady = true`
8. ExoPlayer resumes with existing MediaSource and buffer state

**Bug 2 scenario (stop/exit):**
1. User backs out from TvPlayerSurface
2. `TvPlayerSurface` BackHandler (line 168) → `surface.leaveSurface()` + `onBack()`
3. `onBack()` → `playerController.stop()` (NavHost.kt:127)
4. `playerController.stop()` (PlayerController.kt:111-115) → `playbackSession.pause()` (NOT `stop()`)
5. Session stays alive, just paused
6. User navigates back to detail screen later
7. `LaunchedEffect(info.ref)` (MovieDetailScreen.kt:36-38) → `playerController.prepare(info)`
8. `prepare()` reads `entryInfo.userData?.positionMs` and creates new playback spec
9. But the **session is still holding the old MediaSource** until `resolveAndPrepare()` completes

**Critical finding:** `PlayerController.stop()` calls `playbackSession.pause()`, NOT `playbackSession.stop()`. The actual `playbackSession.stop()` is only called by `PlayerController.reset()` (line 122), which happens on `DisposableEffect.onDispose` when fully leaving the detail screen.

---

## Initial Hypotheses (Now Superseded)

### ~~Hypothesis 1: Server-side transcode throttling~~ (WRONG - not applicable for direct play)
I initially analyzed Jellyfin's `TranscodingThrottler.cs` and found:
- Throttler compares `transcodingPositionTicks` (how far ffmpeg transcoded) vs `downloadPositionTicks` (derived from HLS segment requests)
- Throttle threshold: min 60 seconds
- HLS transcode kill timeout: 60 seconds without ping

But this entire analysis is **irrelevant** because the user confirmed direct play, not transcoding.

### ~~Hypothesis 2: Missing /Sessions/Playing/Stopped call~~ (WRONG - not applicable for direct play)
I found that we never send `POST /Sessions/Playing/Stopped` when backing out, only `reportProgress(IsPaused: true)`. Kodi sends `Stopped` and deletes the PlaySessionId from cache.

But for direct play, there's no transcode job to kill, so this doesn't explain the bugs.

---

## Current Understanding (Direct Play Specific)

### What We Know

1. **Direct play = HTTP byte-range requests to static files**
   - No server-side session state (beyond HTTP connection pooling)
   - No transcode jobs, no throttling
   - ExoPlayer manages all buffering client-side

2. **ExoPlayer buffer state should persist across pause/resume**
   - `pause()` sets `playWhenReady = false`, doesn't clear MediaSource
   - Buffered data stays in memory
   - Resume should just continue from cached buffer

3. **BandwidthTracker shows fast download in Bug 1**
   - High `mbps` value (user confirmed visible in InfoOverlay)
   - But gray bar (buffered position indicator) doesn't increase
   - This suggests: downloading is happening, but either:
     - ExoPlayer is re-downloading already-buffered ranges (why?)
     - Or `exo.bufferedPosition` is not advancing (why?)
     - Or the UI calculation is wrong (unlikely)

4. **Bug 2 shows slow download after stop/resume**
   - Could be:
     - HTTP connection pooling issue (stale connections in OkHttp)
     - BandwidthTracker state issue (total bytes counter not reset?)
     - Server-side connection throttling (if same TCP connection reused)
     - Network layer issue

### What We Don't Know (Need Logs)

**For Bug 1:**
- What's `exo.currentPosition` after resume? (advancing normally?)
- What's `exo.bufferedPosition`? (stuck, or increasing?)
- What's `exo.totalBufferedDuration`? (should equal `bufferedPosition - currentPosition`)
- What's the exact `mbps` value? (e.g., 50 Mbps, 100 Mbps?)
- What's `deltaKB` per second? (continuous download, or bursts?)
- What's `exo.playbackState`? (STATE_READY, STATE_BUFFERING?)

**For Bug 2:**
- What's the "slow" `mbps` value? (e.g., 1 Mbps vs 50 Mbps?)
- Is `deltaKB` actually low, or is the `mbps` calculation wrong?
- What's `buffered=` - is it increasing slowly, or stuck?
- Does stopping and restarting the app fix it? (suggests OkHttp connection pool issue)

**For both:**
- Confirm the exact URL pattern from logs - does it contain `static=true`?
- Check the HTTP headers - any `Range:` requests visible?
- Check if there are multiple parallel range requests (ExoPlayer adaptive streaming)

---

## Possible Root Causes (Speculative)

### Bug 1 Candidates

**Candidate A: ExoPlayer buffer invalidation on pause**
- If ExoPlayer's `LoadControl` or cache logic invalidates buffered data after a long pause
- Or if Android system reclaims buffer memory during pause
- Would explain why it needs to re-download

**Candidate B: Position/seek mismatch**
- If `syncEntryState` saves position A, but ExoPlayer internally is at position B
- On resume, ExoPlayer seeks to position B, invalidating the buffer at position A
- Needs to re-download from position B

**Candidate C: MediaSource recreation on detail screen**
- If entering detail screen triggers `prepare()` (which it does, line 38)
- And `prepare()` creates a new `PlaybackSpec` with new MediaSource
- Old buffer is discarded, new source starts from scratch
- But this should happen BEFORE the user clicks Resume, not after

**Candidate D: HTTP Range header issue**
- ExoPlayer requests `Range: bytes=X-Y`
- Server responds with wrong range or cached response
- ExoPlayer keeps re-requesting the same range

### Bug 2 Candidates

**Candidate A: OkHttp connection pool stale connections**
- After stop, HTTP connections stay in pool
- On resume, reused connection is throttled or slow (server-side TCP window not recovered)
- Need to force new connection or clear pool

**Candidate B: BandwidthTracker state issue**
- `BandwidthTracker.reset()` is called in `prepare()` (PlaybackSession.kt:183)
- But if `prepare()` uses cached spec (line 175-177 in PlayerController), reset might not fire
- Stale bandwidth calculation affects download strategy

**Candidate C: Server-side per-IP or per-session rate limiting**
- If Emby server throttles based on IP or session fingerprint
- Resuming reuses same session/IP, hits rate limit
- New movie = new session, no limit

**Candidate D: Android network layer issue**
- TCP congestion window not reset after idle
- WiFi power-save causing slow ramp-up
- DNS or routing issue after network idle

---

## Source Code Locations

### Key Files

**Player Engine:**
- `player/src/main/java/com/insomnia/player/engine/PlaybackSession.kt`
  - `prepare(spec)` - line 172: sets MediaSource, starts heartbeat
  - `play()` - line 204: sets `playWhenReady = true`
  - `pause()` - line 209: sets `playWhenReady = false`, syncs state
  - `stop()` - line 232: syncs state, calls `stopInternal()` which does `exo.stop()`
  - `syncEntryState()` - line 215: captures `exo.currentPosition`, saves to storage
  - `startHeartbeat()` - line 246: 10-second timer reporting position to storage

- `player/src/main/java/com/insomnia/player/engine/PlaybackSurface.kt`
  - `leaveSurface()` - line 31: calls `session.pause()`
  - Bandwidth logging - line 72-77: logs `mbps`, `deltaKB`, `pos`, `buffered`, `state`

- `player/src/main/java/com/insomnia/player/engine/InsomniaExoPlayer.kt`
  - Buffer config - line 25-32: `minBufferMs=290s`, `maxBufferMs=300s`

- `player/src/main/java/com/insomnia/player/engine/BandwidthTracker.kt`
  - `reset()` - line 30: clears totalBytes and buckets
  - `record()` - line 35: called on every OkHttp read
  - `mbps` - line 49: rolling 3-second window calculation

**Player UI:**
- `player/src/main/java/com/insomnia/player/ui/tv/TvPlayerSurface.kt`
  - BackHandler - line 163-169: calls `leaveSurface()` + `onBack()`
  - Buffered bar - line 227: passes `exo.bufferedPosition` to `PlaybackControllerBar`

- `player/src/main/java/com/insomnia/player/ui/PlaybackControllerBar.kt`
  - Buffer rendering - line 54-55: calculates `bufferedFraction = buffered / duration`
  - Gray bar logic - line 80-100: draws buffered overlay from `playedX + halfGap` to `bufferedX`

**Content/Controller:**
- `content/ui/src/main/java/com/insomnia/content/ui/catalog/player/PlayerController.kt`
  - `prepare()` - line 86-100: captures startMs, launches resolve
  - `play()` - line 102-109: calls `launchResolve()`, then `playbackSession.play()`
  - `stop()` - line 111-116: calls `playbackSession.pause()` (NOT `stop()`!)
  - `reset()` - line 118-133: calls `playbackSession.stop()`, clears all state
  - `resolveAndPrepare()` - line 172-194: calls `getPlaybackSpec`, then `playbackSession.prepare()`
  - Deduplication check - line 175-177: skips resolve if same `itemRef` + `startMs`

- `content/ui/src/main/java/com/insomnia/content/ui/catalog/detail/MovieDetailScreen.kt`
  - Resume button - line 41: `resumePlay = { playerController?.play() }`
  - Play from start - line 42-45: `seekTo(0L)`, then `play()`
  - Auto-prepare - line 36-38: `LaunchedEffect(info.ref) { playerController?.prepare(info) }`

**Navigation:**
- `app/src/main/java/com/insomnia/app/navigation/InsomniaNavHost.kt`
  - Player surface overlay - line 124-129: shows when `isShown`, onBack calls `stop()`

**Emby Provider (for reference):**
- `providers-ts/providers/emby/hooks.ts`
  - `updateEntryState()` - line 53-90: handles position/state updates
  - `reportProgress()` - line 107-125: sends `POST /Sessions/Playing/Progress`
  - `reportStopped()` - line 127-138: sends `POST /Sessions/Playing/Stopped`

---

## Experiments Needed

### Experiment 1: Confirm direct play vs transcode
**Goal:** Verify the PlayMethod and URL pattern

**Steps:**
1. Add logging in `PlaybackSpecExt.kt:toMediaSource()` to print `source.url`
2. Reproduce Bug 1 or Bug 2
3. Check logs for URL pattern:
   - Contains `static=true`? → Direct play
   - Contains `.m3u8`? → HLS transcode
   - Contains `TranscodingUrl` or `stream.ts`? → Transcode

**Expected:** Should see `static=true` (user confirmed direct play)

### Experiment 2: Log buffer state during Bug 1
**Goal:** Understand what ExoPlayer's buffer is doing

**Steps:**
1. Enable logging at `PlaybackSurface.kt:72-77` (already exists as Timber.i with "OT_BW" prefix)
2. Reproduce Bug 1:
   - Start playing
   - Pause for 2+ minutes (go to detail screen)
   - Resume (click Resume button)
   - Wait for buffering/slow buffer refill
3. Capture logs, look for:
   - `pos=` value before pause, after resume
   - `buffered=` value before pause, after resume, during "fast download"
   - `mbps=` value during "fast download"
   - `deltaKB=` per second
   - `state=` (ExoPlayer playback state: 2=BUFFERING, 3=READY)

**Questions to answer:**
- Is `buffered` actually increasing during fast download?
- Is `pos` advancing normally during playback after resume?
- What's the exact `mbps` and `deltaKB` values?
- Does `state` flip between READY and BUFFERING?

### Experiment 3: Log buffer state during Bug 2
**Goal:** Understand the "slow speed" symptom

**Steps:**
1. Same logging as Experiment 2
2. Reproduce Bug 2:
   - Start playing (should be fast)
   - Back out to browse (calls `stop()`)
   - Resume playback (navigate back to detail, prepare, play)
   - Observe slow speed
3. Capture logs, compare:
   - Initial playback: `mbps=` and `deltaKB=` values
   - After resume: `mbps=` and `deltaKB=` values

**Questions to answer:**
- What's "slow" in concrete numbers? (1 Mbps vs 50 Mbps?)
- Is `deltaKB` actually low, or is `mbps` calculation wrong?
- Is `buffered` increasing slowly, or stuck?

### Experiment 4: Force MediaSource recreation on resume
**Goal:** Test if reusing MediaSource causes Bug 1

**Steps:**
1. Modify `MovieDetailScreen.kt:41` to call `prepare()` before `play()`:
   ```kotlin
   val resumePlay = {
       playerController?.prepare(info, info.userData?.positionMs)
       playerController?.play()
   }
   ```
2. Reproduce Bug 1 scenario
3. Check if the bug still occurs

**Expected:**
- If bug disappears → reusing MediaSource is the issue
- If bug persists → problem is elsewhere

### Experiment 5: Clear OkHttp connection pool on resume
**Goal:** Test if stale HTTP connections cause Bug 2

**Steps:**
1. Modify `PlayerController.prepare()` to clear connection pool:
   ```kotlin
   fun prepare(entryInfo: EntryInfo, startMs: Long? = null) {
       // ... existing code ...
       
       // Force new HTTP connections
       _client?.httpClient?.connectionPool?.evictAll()
       
       // ... rest of existing code ...
   }
   ```
2. Reproduce Bug 2 scenario
3. Check if speed improves on resume

**Expected:**
- If speed improves → HTTP connection reuse is the issue
- If still slow → problem is elsewhere (server-side, network, or ExoPlayer)

### Experiment 6: Reset BandwidthTracker on every prepare
**Goal:** Verify BandwidthTracker state doesn't affect speed

**Steps:**
1. Check if `BandwidthTracker.reset()` is called in all prepare paths
2. Currently called at `PlaybackSession.prepare():183`
3. But if `PlayerController.play()` skips resolve (line 175-177), reset might not fire
4. Add explicit reset in `PlayerController.play()`:
   ```kotlin
   fun play() {
       _isShown.value = true
       BandwidthTracker.reset()  // Add this
       launchResolve(onComplete = { ... })
   }
   ```
5. Reproduce Bug 2, check if it affects the slow speed

### Experiment 7: Compare "first video of the day" vs resume
**Goal:** Understand why "new movie on new day" is always fast

**Steps:**
1. Cold start app, play a video → measure `mbps` and `deltaKB`
2. Stop, wait 5 minutes, resume same video → measure again
3. Stop, wait 24 hours, play different video → measure again
4. Compare all three scenarios

**Questions to answer:**
- Is "new day" fast because of app restart (clears all state)?
- Or because of different video (different HTTP connection)?
- Or because of time gap (server resets something)?

### Experiment 8: Monitor HTTP Range headers
**Goal:** See if ExoPlayer is requesting wrong byte ranges

**Steps:**
1. Add OkHttp logging interceptor to log all request headers:
   ```kotlin
   val loggingInterceptor = Interceptor { chain ->
       val request = chain.request()
       Timber.d("HTTP ${request.method} ${request.url}")
       request.headers.forEach { (name, value) ->
           Timber.d("  $name: $value")
       }
       chain.proceed(request)
   }
   ```
2. Add to `PlaybackSpecExt.kt:toMediaSource()` OkHttp builder (line 42-46)
3. Reproduce Bug 1, look for `Range:` headers in logs
4. Check if ranges are sequential or re-requesting same ranges

---

## Next Steps

1. **Run Experiment 2 and 3** to get concrete log data
   - Without this, we're guessing blind
   - Need actual `mbps`, `buffered`, `pos`, `deltaKB` values

2. **Based on log data, narrow down to one of the candidates**
   - If `buffered` is stuck → ExoPlayer buffer issue (Candidate A or B)
   - If `buffered` increasing slowly → network/HTTP issue (Candidate A/C/D for Bug 2)
   - If `buffered` jumping around → seeking/position issue (Candidate B for Bug 1)

3. **Run targeted experiment to test the hypothesis**
   - E.g., if HTTP connection pool suspected, run Experiment 5

4. **Implement fix and verify**

---

## References

### Jellyfin Source (for transcode reference, not applicable here)
- `TranscodingThrottler.cs:145-195` - Throttle decision logic
- `TranscodeManager.cs:145-190` - Kill timer and ping logic
- `DynamicHlsController.cs:1987-1994` - Where `DownloadPositionTicks` is set
- `PlaystateController.cs:217-260` - Progress/Ping/Stopped endpoints
- HLS transcode kill timeout: **60 seconds**
- Throttle threshold: **min 60 seconds**
- Throttle timer: **5 seconds**

### Kodi Plugin Reference
- Progress heartbeat: **50 seconds** (plugin.video.emby, player.py:811)
- Sends `/Sessions/Playing/Stopped` on exit (api.py:801-804)
- Deletes PlaySessionId from cache on stop

### Our Implementation
- Progress heartbeat: **10 seconds** (`DEFAULT_PROGRESS_INTERVAL_MS`)
- **Never sends `/Sessions/Playing/Stopped` when backing out** (only sends paused progress)
- **Never sends `/Sessions/Playing/Ping`** (relies on progress to keep session alive)
- `PlayerController.stop()` calls `playbackSession.pause()`, NOT `stop()`

---

## Open Questions

1. Why does "new movie on new day" always have fast speed?
   - App restart clears something?
   - Server resets session state overnight?
   - Different content/server?

2. What's the actual `mbps` value difference between "fast" and "slow"?
   - Need concrete numbers from logs

3. Is the gray bar UI calculation correct?
   - Formula: `bufferedFraction = buffered / duration`
   - Should be correct, but verify with log data

4. Does Android system reclaim ExoPlayer buffer memory during long pause?
   - Possible on low-memory devices
   - Would explain Bug 1

5. Is there any Emby server-side caching or CDN involved?
   - Could explain slow speed if cache is cold

---

## Potential Fixes (Speculative, Pending Experiment Results)

### For Bug 1

**Fix A: Force MediaSource recreation on resume from detail**
- Modify `MovieDetailScreen.kt:41` to call `prepare()` before `play()`
- Ensures fresh MediaSource with correct position

**Fix B: Explicit seek before resume**
- Capture position before pause, seek to it before play
- Ensures ExoPlayer position matches saved position

**Fix C: Increase min buffer duration**
- If ExoPlayer invalidates buffer after pause, increase `minBufferMs` to cover longer pauses
- Trade-off: more memory usage

### For Bug 2

**Fix A: Clear HTTP connection pool on prepare**
- Call `httpClient.connectionPool.evictAll()` before creating new MediaSource
- Forces fresh TCP connections

**Fix B: Send `/Sessions/Playing/Stopped` when backing out**
- Modify `PlayerController.stop()` to send stopped event
- Cleans up server-side session state

**Fix C: Reset BandwidthTracker on every play**
- Ensure bandwidth measurement doesn't carry over stale state

**Fix D: Create new OkHttpClient instance per prepare**
- Don't reuse httpClient in PlaybackSpec
- Trade-off: more overhead, but guarantees fresh state

---

## End of Analysis

**Status:** Needs log data from Experiments 2 and 3 to proceed  
**Next action:** Run experiments, capture logs, reconvene with concrete data


sync resume position
stop heartbeat on pause
log range request
log transcode type