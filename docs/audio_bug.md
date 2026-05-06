  Root Cause: Race Condition in shutdown() - Navigation Cancels Cleanup

  The bug is in OpenTunePlayerScreen.kt:83-100, specifically the order of operations in the shutdown() function.

  suspend fun shutdown(userInitiatedExit: Boolean) {
      // ...
      if (userInitiatedExit) {
          withContext(Dispatchers.Main) { onExitState.value() }  // <-- PROBLEM: navigation BEFORE cleanup
      }
      val pos = withContext(Dispatchers.Main) { exo.currentPosition }
      withContext(Dispatchers.IO) { mediaStateStore.upsertPosition(instanceKey, pos) }
      s.hooks.onStop(pos)
      withContext(Dispatchers.Main) { exo.release() }           // <-- This may never execute
      s.onPlaybackDispose()
      if (userInitiatedExit) {
          withContext(Dispatchers.Main) { onExitState.value() }
      }
  }

  The sequence of events that causes the bug:

  1. User presses DPAD Back -> BackHandler calls scope.launch { shutdown(userInitiatedExit = true) }
  2. shutdown sets released = true via compareAndSet
  3. shutdown calls onExit() which triggers nav.popBackStack()
  4. Navigation removes OpenTunePlayerScreen from composition
  5. The rememberCoroutineScope() is tied to the composition -> coroutine gets cancelled
  6. The withContext(Dispatchers.Main) { exo.release() } line never executes because the coroutine was cancelled
  7. runBlocking { shutdown(false) } in DisposableEffect.onDispose runs, but released is already true, so it skips cleanup
  8. ExoPlayer is never released -> audio continues playing forever

  Why another app (iQIYI) stops it: Android's audio focus system. When iQIYI requests audio focus, Android forces the orphaned ExoPlayer to pause/stop because it loses audio focus. But the player object itself
  is still alive in memory, silently holding audio resources.

  Why the new video has no sound: When you play a new video, the new ExoPlayer instance can't acquire audio focus because the old (orphaned) player still holds it.

  The fix is to call onExit AFTER exo.release() and onPlaybackDispose(), ensuring cleanup completes before navigation disposes the composition and cancels the coroutine.

