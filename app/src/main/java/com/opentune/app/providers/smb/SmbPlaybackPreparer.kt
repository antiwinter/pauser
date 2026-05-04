package com.opentune.app.providers.smb

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.compose.material3.Text as M3Text
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.R
import com.opentune.app.playback.PlaybackPreparer
import com.opentune.app.providers.OpenTuneProviderIds
import com.opentune.player.AudioFallbackMedia
import com.opentune.player.OPEN_TUNE_PLAYER_LOG
import com.opentune.player.OpenTuneExoPlayer
import com.opentune.player.OpenTunePlaybackResumeStore
import com.opentune.player.OpenTunePlayerScreen
import com.opentune.player.createAudioDecodeFallbackListener
import com.opentune.smb.SmbCredentials
import com.opentune.smb.SmbDataSource
import com.opentune.smb.SmbPlaybackHooks
import com.opentune.smb.SmbSession
import com.opentune.storage.OpenTuneDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
object SmbPlaybackPreparer : PlaybackPreparer {

    @Composable
    override fun Render(
        _app: OpenTuneApplication,
        database: OpenTuneDatabase,
        sourceId: Long,
        itemRefDecoded: String,
        _startPositionMs: Long,
        onExit: () -> Unit,
    ) {
        val context = LocalContext.current
        val pathWin = itemRefDecoded.replace('/', '\\')
        val audioUnsupportedMessage = stringResource(R.string.smb_audio_unsupported_banner)
        val mainHandler = remember { Handler(Looper.getMainLooper()) }

        var session by remember { mutableStateOf<SmbSession?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var showAudioUnsupportedBanner by remember { mutableStateOf(false) }

        LaunchedEffect(sourceId) {
            error = null
            session = null
            try {
                val src = withContext(Dispatchers.IO) { database.smbSourceDao().getById(sourceId) }
                    ?: error("SMB source not found")
                session = withContext(Dispatchers.IO) {
                    SmbSession.open(
                        SmbCredentials(
                            host = src.host,
                            shareName = src.shareName,
                            username = src.username,
                            password = src.password,
                            domain = src.domain,
                        ),
                    )
                }
            } catch (e: Exception) {
                error = e.message
            }
        }

        DisposableEffect(session) {
            val openSession = session
            onDispose { openSession?.close() }
        }

        LaunchedEffect(session?.share, pathWin) {
            showAudioUnsupportedBanner = false
        }

        val share = session?.share
        val exoPlayer = remember(share, pathWin) {
            val sh = share ?: return@remember null
            Log.i(OPEN_TUNE_PLAYER_LOG, "[smb] remember ExoPlayer pathWin=$pathWin (single instance; in-place audio-off on failure)")
            val http = OkHttpClient()
            val exo = OpenTuneExoPlayer.create(context, http)
            val resumeKey = "${OpenTuneProviderIds.FILE_SHARE}_${sourceId}_$pathWin"
            exo.playbackParameters = PlaybackParameters(
                OpenTunePlaybackResumeStore.readSpeed(context, resumeKey),
            )
            val factory = DataSource.Factory {
                Log.d(OPEN_TUNE_PLAYER_LOG, "[smb] createDataSource pathWin=$pathWin")
                SmbDataSource(sh, pathWin)
            }
            val mediaSource = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(Uri.parse("https://local.invalid/video")))
            exo.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateName = when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> playbackState.toString()
                        }
                        val err = exo.playerError
                        Log.d(
                            OPEN_TUNE_PLAYER_LOG,
                            "[smb] playbackState=$stateName playWhenReady=${exo.playWhenReady} isLoading=${exo.isLoading} error=${err?.message}",
                        )
                        if (err != null) {
                            Log.e(OPEN_TUNE_PLAYER_LOG, "[smb] playerError detail", err)
                        }
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        Log.d(OPEN_TUNE_PLAYER_LOG, "[smb] playWhenReady=$playWhenReady reason=$reason")
                    }

                    override fun onIsLoadingChanged(isLoading: Boolean) {
                        Log.d(OPEN_TUNE_PLAYER_LOG, "[smb] isLoading=$isLoading")
                    }
                },
            )
            Log.d(OPEN_TUNE_PLAYER_LOG, "[smb] prepare pathWin=$pathWin")
            exo.setMediaSource(mediaSource)
            exo.playWhenReady = true
            exo.prepare()
            exo to mediaSource
        }

        DisposableEffect(exoPlayer) {
            val pair = exoPlayer
            if (pair == null) {
                onDispose { }
            } else {
                val exo = pair.first
                val mediaSource = pair.second
                val fallbackListener = exo.createAudioDecodeFallbackListener(
                    logTag = OPEN_TUNE_PLAYER_LOG,
                    mainHandler = mainHandler,
                    media = AudioFallbackMedia.MediaSourcePayload(mediaSource),
                    onAudioDisabled = { showAudioUnsupportedBanner = true },
                )
                exo.addListener(fallbackListener)
                onDispose { exo.removeListener(fallbackListener) }
            }
        }

        when {
            error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(48.dp),
                ) {
                    Text("Error: $error")
                    Button(onClick = onExit) { Text("Back") }
                }
            }
            exoPlayer != null -> {
                val exo = exoPlayer.first
                val smbResumeKey = remember(sourceId, pathWin) { "${OpenTuneProviderIds.FILE_SHARE}_${sourceId}_$pathWin" }
                val smbResumeStartMs = remember(sourceId, pathWin) {
                    OpenTunePlaybackResumeStore.readResumePosition(context, smbResumeKey).let { p ->
                        if (p >= 0L) p else 0L
                    }
                }
                OpenTunePlayerScreen(
                    exoPlayer = exo,
                    hooks = SmbPlaybackHooks,
                    startPositionMs = smbResumeStartMs,
                    onExit = onExit,
                    resumeProgressKey = smbResumeKey,
                    topBanner = if (showAudioUnsupportedBanner) {
                        {
                            M3Text(
                                text = audioUnsupportedMessage,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(horizontal = 24.dp, vertical = 16.dp)
                                    .background(Color.Black.copy(alpha = 0.72f))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                color = Color.White,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            else -> {
                Text("Loading…", modifier = Modifier.padding(48.dp))
            }
        }
    }
}
