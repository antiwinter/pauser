package com.opentune.smb

import com.opentune.provider.OpenTunePlaybackHooks
import com.opentune.provider.StreamRegistrarHolder

/**
 * Revokes all stream tokens registered for this playback session when ExoPlayer is released.
 * Token revocation removes the entries from [OpenTuneServer]'s registry so subsequent requests
 * return 404 and outstanding SMB sessions tied to those streams are closed by the server.
 */
class SmbPlaybackHooks(private val tokenUrls: List<String>) : OpenTunePlaybackHooks {

    override fun progressIntervalMs(): Long = 0L

    override suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float) = Unit

    override suspend fun onProgressTick(positionMs: Long, playbackRate: Float, isPaused: Boolean) = Unit

    override suspend fun onStop(positionMs: Long) = Unit

    override fun onDispose() {
        val registrar = StreamRegistrarHolder.get()
        tokenUrls.forEach { registrar.revokeToken(it) }
    }
}
