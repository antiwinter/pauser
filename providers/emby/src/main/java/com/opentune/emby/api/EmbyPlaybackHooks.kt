package com.opentune.emby.api

import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.emby.api.dto.PlaybackProgressInfo
import com.opentune.emby.api.dto.PlaybackStartInfo
import com.opentune.emby.api.dto.PlaybackStopInfo
import com.opentune.provider.OpenTunePlaybackHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EmbyPlaybackHooks(
    private val deviceProfile: DeviceProfile,
    private val itemId: String,
    private val playMethod: String,
    private val playSessionId: String?,
    private val mediaSourceId: String?,
    private val liveStreamId: String?,
    private val baseUrl: String,
    private val userId: String,
    private val accessToken: String,
) : OpenTunePlaybackHooks {

    private fun repository(): EmbyRepository =
        EmbyRepository(
            baseUrl = baseUrl,
            userId = userId,
            accessToken = accessToken,
            deviceProfile = deviceProfile,
        )

    override fun progressIntervalMs(): Long = 10_000L

    override suspend fun onPlaybackReady(positionMs: Long, playbackRate: Float) {
        val ticks = positionMs * 10_000L
        withContext(Dispatchers.IO) {
            repository().reportPlaying(
                PlaybackStartInfo(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    playSessionId = playSessionId,
                    liveStreamId = liveStreamId,
                    playMethod = playMethod,
                    positionTicks = ticks,
                    playbackRate = playbackRate,
                ),
            )
        }
    }

    override suspend fun onProgressTick(positionMs: Long, playbackRate: Float) {
        val ticks = positionMs * 10_000L
        withContext(Dispatchers.IO) {
            repository().reportProgress(
                PlaybackProgressInfo(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    playSessionId = playSessionId,
                    liveStreamId = liveStreamId,
                    playMethod = playMethod,
                    positionTicks = ticks,
                    playbackRate = playbackRate,
                ),
            )
        }
    }

    override suspend fun onStop(positionMs: Long) {
        val ticks = positionMs * 10_000L
        withContext(Dispatchers.IO) {
            repository().reportStopped(
                PlaybackStopInfo(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    playSessionId = playSessionId,
                    liveStreamId = liveStreamId,
                    positionTicks = ticks,
                ),
            )
        }
    }
}
