package com.opentune.app.providers.emby

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.opentune.app.OpenTuneApplication
import com.opentune.app.providers.OpenTuneProviderIds
import com.opentune.app.playback.EmbyPlaybackHooks
import com.opentune.app.playback.PlaybackPreparer
import com.opentune.emby.api.EmbyPlaybackUrlResolver
import com.opentune.player.AudioFallbackMedia
import com.opentune.player.OPEN_TUNE_PLAYER_LOG
import com.opentune.player.OpenTuneExoPlayer
import com.opentune.player.OpenTunePlaybackResumeStore
import com.opentune.player.OpenTunePlayerScreen
import com.opentune.player.createAudioDecodeFallbackListener
import com.opentune.storage.OpenTuneDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
object EmbyPlaybackPreparer : PlaybackPreparer {

    @Composable
    override fun Render(
        app: OpenTuneApplication,
        _database: OpenTuneDatabase,
        sourceId: Long,
        itemRefDecoded: String,
        startPositionMs: Long,
        onExit: () -> Unit,
    ) {
        val context = LocalContext.current
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        var error by remember { mutableStateOf<String?>(null) }
        var ready by remember { mutableStateOf<HttpLibraryReady?>(null) }

        LaunchedEffect(sourceId, itemRefDecoded, startPositionMs) {
            error = null
            ready = null
            try {
                val server = withContext(Dispatchers.IO) { app.database.embyServerDao().getById(sourceId) }
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
                    repo.getPlaybackInfo(itemRefDecoded, startTimeTicks = startTicks)
                }
                val source = info.mediaSources.firstOrNull() ?: error("No media sources")
                val url = EmbyPlaybackUrlResolver.resolve(server.baseUrl, source)
                val playMethod = EmbyPlaybackUrlResolver.playMethod(source)
                val playSessionId = info.playSessionId
                val mediaSourceId = source.id
                val liveStreamId = source.liveStreamId

                val exo = OpenTuneExoPlayer.create(context, okHttp)
                val resumeKey = "${OpenTuneProviderIds.HTTP_LIBRARY}_${sourceId}_$itemRefDecoded"
                exo.playbackParameters = PlaybackParameters(
                    OpenTunePlaybackResumeStore.readSpeed(context, resumeKey),
                )
                exo.playWhenReady = true
                val item = MediaItem.fromUri(Uri.parse(url))
                exo.setMediaItem(item)
                exo.prepare()

                val hooks = EmbyPlaybackHooks(
                    database = app.database,
                    deviceProfile = app.deviceProfile,
                    serverId = sourceId,
                    itemId = itemRefDecoded,
                    playMethod = playMethod,
                    playSessionId = playSessionId,
                    mediaSourceId = mediaSourceId,
                    liveStreamId = liveStreamId,
                    baseUrl = server.baseUrl,
                    userId = server.userId,
                    accessToken = server.accessToken,
                )
                ready = HttpLibraryReady(exo = exo, hooks = hooks, mediaItem = item)
            } catch (e: Exception) {
                error = e.message ?: "Playback failed"
            }
        }

        val r = ready
        DisposableEffect(r?.exo, r?.mediaItem) {
            val exo = r?.exo
            val item = r?.mediaItem
            if (exo == null || item == null) {
                onDispose { }
            } else {
                val listener = exo.createAudioDecodeFallbackListener(
                    logTag = OPEN_TUNE_PLAYER_LOG,
                    mainHandler = mainHandler,
                    media = AudioFallbackMedia.MediaItemPayload(item),
                    onAudioDisabled = { },
                )
                exo.addListener(listener)
                onDispose { exo.removeListener(listener) }
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
                r != null -> {
                    OpenTunePlayerScreen(
                        exoPlayer = r.exo,
                        hooks = r.hooks,
                        startPositionMs = startPositionMs,
                        onExit = onExit,
                        resumeProgressKey = "${OpenTuneProviderIds.HTTP_LIBRARY}_${sourceId}_$itemRefDecoded",
                    )
                }
                else -> {
                    Text("Loading…", modifier = Modifier.padding(48.dp))
                }
            }
        }
    }

    private data class HttpLibraryReady(
        val exo: ExoPlayer,
        val hooks: EmbyPlaybackHooks,
        val mediaItem: MediaItem,
    )
}
