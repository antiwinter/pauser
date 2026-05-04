package com.opentune.app.ui.emby

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.EmbyRepository
import com.opentune.app.OpenTuneApplication
import com.opentune.app.playback.EmbyPlaybackHooks
import com.opentune.emby.api.EmbyPlaybackUrlResolver
import com.opentune.player.OpenTuneExoPlayer
import com.opentune.player.OpenTunePlaybackResumeStore
import com.opentune.player.OpenTunePlayerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun EmbyPlayerRoute(
    app: OpenTuneApplication,
    serverId: Long,
    itemId: String,
    startPositionMs: Long,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf<EmbyPlayerRouteReady?>(null) }

    LaunchedEffect(serverId, itemId, startPositionMs) {
        error = null
        ready = null
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
            val playMethod = EmbyPlaybackUrlResolver.playMethod(source)
            val playSessionId = info.playSessionId
            val mediaSourceId = source.id
            val liveStreamId = source.liveStreamId

            val exo = OpenTuneExoPlayer.create(context, okHttp)
            val resumeKey = "emby_${serverId}_$itemId"
            exo.playbackParameters = PlaybackParameters(
                OpenTunePlaybackResumeStore.readSpeed(context, resumeKey),
            )
            exo.playWhenReady = true
            exo.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            exo.prepare()

            val hooks = EmbyPlaybackHooks(
                database = app.database,
                deviceProfile = app.deviceProfile,
                serverId = serverId,
                itemId = itemId,
                playMethod = playMethod,
                playSessionId = playSessionId,
                mediaSourceId = mediaSourceId,
                liveStreamId = liveStreamId,
                baseUrl = server.baseUrl,
                userId = server.userId,
                accessToken = server.accessToken,
            )
            ready = EmbyPlayerRouteReady(exo = exo, hooks = hooks)
        } catch (e: Exception) {
            error = e.message ?: "Playback failed"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when {
            error != null -> {
                Text(
                    text = error!!,
                    modifier = Modifier.padding(48.dp),
                )
                Button(onClick = onExit) { Text("Back") }
            }
            ready != null -> {
                OpenTunePlayerScreen(
                    exoPlayer = ready!!.exo,
                    hooks = ready!!.hooks,
                    startPositionMs = startPositionMs,
                    onExit = onExit,
                    resumeProgressKey = "emby_${serverId}_$itemId",
                )
            }
            else -> {
                Text("Loading…", modifier = Modifier.padding(48.dp))
            }
        }
    }
}

private data class EmbyPlayerRouteReady(
    val exo: ExoPlayer,
    val hooks: EmbyPlaybackHooks,
)
