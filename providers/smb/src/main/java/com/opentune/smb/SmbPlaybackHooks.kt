package com.opentune.smb

import com.opentune.provider.OpenTunePlaybackHooks

/** SMB local files — no server session reporting. */
object SmbPlaybackHooks : OpenTunePlaybackHooks {
    override fun progressIntervalMs(): Long = 0L

    override suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float) = Unit

    override suspend fun onProgressTick(positionMs: Long, playbackRate: Float) = Unit

    override suspend fun onStop(positionMs: Long) = Unit
}
