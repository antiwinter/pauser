package com.opentune.app.ui.smb

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.hierynomus.smbj.share.DiskShare
import com.opentune.player.OpenTuneExoPlayer
import com.opentune.player.OpenTunePlayerView
import com.opentune.smb.SmbDataSource
import okhttp3.OkHttpClient

private const val SMB_PLAYER_LOG_TAG = "OpenTuneSmbPlayer"

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun SmbVideoPlayer(
    share: DiskShare,
    unixStylePath: String,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val pathWin = unixStylePath.replace('/', '\\')
    val player = remember(share, pathWin) {
        val http = OkHttpClient()
        val exo = OpenTuneExoPlayer.create(context, http)
        val factory = DataSource.Factory {
            Log.d(SMB_PLAYER_LOG_TAG, "createDataSource for pathWin=$pathWin")
            SmbDataSource(share, pathWin)
        }
        val source = ProgressiveMediaSource.Factory(factory)
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
                        SMB_PLAYER_LOG_TAG,
                        "playbackState=$stateName playWhenReady=${exo.playWhenReady} isLoading=${exo.isLoading} error=${err?.message}",
                    )
                    if (err != null) {
                        Log.e(SMB_PLAYER_LOG_TAG, "playerError detail", err)
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    Log.d(SMB_PLAYER_LOG_TAG, "playWhenReady=$playWhenReady reason=$reason")
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(
                        SMB_PLAYER_LOG_TAG,
                        "onPlayerError code=${error.errorCode} msg=${error.message}",
                        error,
                    )
                }

                override fun onIsLoadingChanged(isLoading: Boolean) {
                    Log.d(SMB_PLAYER_LOG_TAG, "isLoading=$isLoading")
                }
            },
        )
        Log.d(SMB_PLAYER_LOG_TAG, "prepare pathWin=$pathWin")
        exo.setMediaSource(source)
        exo.playWhenReady = true
        exo.prepare()
        exo
    }

    DisposableEffect(player) {
        val p = player
        onDispose { p.release() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        OpenTunePlayerView(player = player, modifier = Modifier.fillMaxSize())
        Button(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Text("Close")
        }
    }
}
