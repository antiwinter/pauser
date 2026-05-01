package com.opentune.app.ui.player

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.EmbyRepository
import com.opentune.app.OpenTuneApplication
import com.opentune.emby.api.EmbyPlaybackUrlResolver
import com.opentune.emby.api.dto.PlaybackProgressInfo
import com.opentune.emby.api.dto.PlaybackStartInfo
import com.opentune.emby.api.dto.PlaybackStopInfo
import com.opentune.player.OpenTuneExoPlayer
import com.opentune.player.OpenTunePlayerView
import com.opentune.storage.PlaybackProgressEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun PlayerRoute(
    app: OpenTuneApplication,
    serverId: Long,
    itemId: String,
    startPositionMs: Long,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var playMethod by remember { mutableStateOf("DirectPlay") }
    var playSessionId by remember { mutableStateOf<String?>(null) }
    var mediaSourceId by remember { mutableStateOf<String?>(null) }
    var liveStreamId by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var seekDone by remember { mutableStateOf(false) }

    val onExitState = rememberUpdatedState(onExit)

    suspend fun persistStopAndReport(exo: androidx.media3.exoplayer.ExoPlayer) {
        val posMs = exo.currentPosition
        val posTicks = posMs * 10_000L
        withContext(Dispatchers.IO) {
            val server = app.database.embyServerDao().getById(serverId) ?: return@withContext
            val repo = EmbyRepository(
                baseUrl = server.baseUrl,
                userId = server.userId,
                accessToken = server.accessToken,
                deviceProfile = app.deviceProfile,
            )
            repo.reportStopped(
                PlaybackStopInfo(
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    playSessionId = playSessionId,
                    liveStreamId = liveStreamId,
                    positionTicks = posTicks,
                ),
            )
            app.database.playbackProgressDao().upsert(
                PlaybackProgressEntity(
                    key = "${serverId}_$itemId",
                    serverId = serverId,
                    itemId = itemId,
                    positionMs = posMs,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    LaunchedEffect(serverId, itemId, startPositionMs) {
        seekDone = false
        try {
            val server = withContext(Dispatchers.IO) { app.database.embyServerDao().getById(serverId) }
                ?: error("Server not found")
            val okHttp = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("X-Emby-Token", server.accessToken)
                        .build()
                    chain.proceed(req)
                }
                .build()
            val repo = EmbyRepository(
                baseUrl = server.baseUrl,
                userId = server.userId,
                accessToken = server.accessToken,
                deviceProfile = app.deviceProfile,
            )
            val startTicks = if (startPositionMs > 0) startPositionMs * 10_000L else null
            val info = withContext(Dispatchers.IO) {
                repo.getPlaybackInfo(itemId, startTimeTicks = startTicks)
            }
            val source = info.mediaSources.firstOrNull() ?: error("No media sources")
            val url = EmbyPlaybackUrlResolver.resolve(server.baseUrl, source)
            playMethod = EmbyPlaybackUrlResolver.playMethod(source)
            playSessionId = info.playSessionId
            mediaSourceId = source.id
            liveStreamId = source.liveStreamId

            val p = OpenTuneExoPlayer.create(context, okHttp)
            p.playWhenReady = true
            p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            p.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && startPositionMs > 0 && !seekDone) {
                            p.seekTo(startPositionMs)
                            seekDone = true
                        }
                    }
                },
            )
            p.prepare()

            withContext(Dispatchers.IO) {
                repo.reportPlaying(
                    PlaybackStartInfo(
                        itemId = itemId,
                        mediaSourceId = source.id,
                        playSessionId = info.playSessionId,
                        liveStreamId = source.liveStreamId,
                        playMethod = playMethod,
                        positionTicks = startTicks,
                        playbackRate = speed,
                    ),
                )
            }
            player = p
        } catch (e: Exception) {
            error = e.message ?: "Playback failed"
        }
    }

    DisposableEffect(player) {
        val p = player
        onDispose {
            p?.release()
        }
    }

    val exoPlayer = player
    LaunchedEffect(exoPlayer, playSessionId, mediaSourceId, playMethod, speed) {
        val exo = exoPlayer ?: return@LaunchedEffect
        val server = withContext(Dispatchers.IO) { app.database.embyServerDao().getById(serverId) }
            ?: return@LaunchedEffect
        val repo = EmbyRepository(
            baseUrl = server.baseUrl,
            userId = server.userId,
            accessToken = server.accessToken,
            deviceProfile = app.deviceProfile,
        )
        while (isActive) {
            delay(10_000)
            if (exo.isPlaying) {
                val posTicks = exo.currentPosition * 10_000L
                withContext(Dispatchers.IO) {
                    repo.reportProgress(
                        PlaybackProgressInfo(
                            itemId = itemId,
                            mediaSourceId = mediaSourceId,
                            playSessionId = playSessionId,
                            liveStreamId = liveStreamId,
                            playMethod = playMethod,
                            positionTicks = posTicks,
                            playbackRate = speed,
                        ),
                    )
                }
            }
        }
    }

    LaunchedEffect(exoPlayer, speed) {
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    BackHandler {
        scope.launch {
            val exo = player
            if (exo != null) {
                persistStopAndReport(exo)
            }
            onExitState.value()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        if (error != null) {
            Text(
                text = error!!,
                modifier = Modifier.padding(48.dp),
            )
            Button(onClick = onExit) { Text("Back") }
        } else if (exoPlayer != null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                OpenTunePlayerView(player = exoPlayer, modifier = Modifier.fillMaxSize())
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(1f, 1.25f, 1.5f, 2f).forEach { s ->
                        Button(onClick = { speed = s }) {
                            Text("${s}x")
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                persistStopAndReport(exoPlayer)
                                onExit()
                            }
                        },
                    ) {
                        Text("Exit")
                    }
                }
            }
        } else {
            Text("Loading…", modifier = Modifier.padding(48.dp))
        }
    }
}
