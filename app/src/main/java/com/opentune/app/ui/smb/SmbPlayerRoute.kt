package com.opentune.app.ui.smb

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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.compose.material3.Text as M3Text
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.R
import com.opentune.player.OpenTuneExoPlayer
import com.opentune.player.OpenTunePlayerScreen
import com.opentune.smb.SmbCredentials
import com.opentune.smb.SmbDataSource
import com.opentune.smb.SmbPlaybackHooks
import com.opentune.smb.SmbSession
import com.opentune.storage.OpenTuneDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "OpenTuneSmbPlayer"

/** Grep this tag for SMB open → ExoPlayer → datasource lifecycle (path, audio retry). */
private const val SMB_PLAYBACK_LOG = "OpenTuneSmbPlayback"

private fun PlaybackException.isAudioDecodeFailure(): Boolean {
    val texts = sequence {
        yield(message)
        var t: Throwable? = cause
        while (t != null) {
            yield(t.message)
            t = t.cause
        }
    }
    return texts.any { it?.contains("MediaCodecAudioRenderer", ignoreCase = true) == true } ||
        texts.any { it?.contains("AudioSink", ignoreCase = true) == true }
}

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun SmbPlayerRoute(
    database: OpenTuneDatabase,
    sourceId: Long,
    filePath: String,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val pathWin = filePath.replace('/', '\\')
    val audioUnsupportedMessage = stringResource(R.string.smb_audio_unsupported_banner)

    var session by remember { mutableStateOf<SmbSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAudioUnsupportedBanner by remember { mutableStateOf(false) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

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
    // One ExoPlayer per (share, path): audio-off recovery mutates this instance instead of
    // remember(disableAudio) rebuild — second prepare never reached READY for some MKVs.
    val exoPlayer = remember(share, pathWin) {
        val sh = share ?: return@remember null
        Log.i(SMB_PLAYBACK_LOG, "remember ExoPlayer pathWin=$pathWin (single instance; in-place audio-off on failure)")
        val http = OkHttpClient()
        val exo = OpenTuneExoPlayer.create(context, http)
        val audioDecodeRetryTaken = AtomicBoolean(false)
        val factory = DataSource.Factory {
            Log.d(LOG_TAG, "createDataSource for pathWin=$pathWin")
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
                        LOG_TAG,
                        "playbackState=$stateName playWhenReady=${exo.playWhenReady} isLoading=${exo.isLoading} error=${err?.message}",
                    )
                    if (err != null) {
                        Log.e(LOG_TAG, "playerError detail", err)
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    Log.d(LOG_TAG, "playWhenReady=$playWhenReady reason=$reason")
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (error.isAudioDecodeFailure()) {
                        if (!audioDecodeRetryTaken.compareAndSet(false, true)) {
                            Log.w(LOG_TAG, "Audio error after in-place retry; ignoring code=${error.errorCode}")
                            return
                        }
                        Log.w(
                            LOG_TAG,
                            "Audio decode failed; disabling audio on same ExoPlayer. code=${error.errorCode}",
                            error,
                        )
                        mainHandler.post {
                            showAudioUnsupportedBanner = true
                            exo.trackSelectionParameters = exo.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                                .build()
                            exo.stop()
                            exo.setMediaSource(mediaSource)
                            exo.playWhenReady = true
                            exo.prepare()
                            Log.d(SMB_PLAYBACK_LOG, "in-place audio-off prepare issued")
                        }
                    } else {
                        Log.e(
                            LOG_TAG,
                            "onPlayerError code=${error.errorCode} msg=${error.message}",
                            error,
                        )
                    }
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    Log.d(LOG_TAG, "isLoading=$isLoading")
                }
            },
        )
        Log.d(LOG_TAG, "prepare pathWin=$pathWin")
        exo.setMediaSource(mediaSource)
        exo.playWhenReady = true
        exo.prepare()
        exo
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
            OpenTunePlayerScreen(
                exoPlayer = exoPlayer,
                hooks = SmbPlaybackHooks,
                startPositionMs = 0L,
                onExit = onExit,
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
