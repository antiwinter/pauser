package com.insomnia.player.ui.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.insomnia.core.theme.ScrimMedium
import com.insomnia.player.PlayerSurfaceController
import com.insomnia.player.ui.InfoOverlay
import com.insomnia.player.ui.PlaybackHostEffects
import com.insomnia.player.ui.rememberInfoOverlayState
import com.insomnia.player.engine.rememberPlaybackSurface

/**
 * Live-stream player surface. No controller bar, no pause/seek — up/down switch channels,
 * left/right switch sources, center toggles the info overlay, MENU opens the channel list.
 */
@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
fun TvLivePlayerSurface(
    controller: PlayerSurfaceController,
    onBack: () -> Unit,
) {
    val session = controller.playbackSession
    val spec by session.currentSpecFlow.collectAsState()
    val specValue = spec ?: run {
        LivePlayerLoadingOverlay(onBack = onBack)
        return
    }
    TvLivePlayerSurfaceContent(
        controller = controller,
        spec = specValue,
        onBack = onBack,
    )
}

@Composable
private fun LivePlayerLoadingOverlay(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Loading channel…",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    BackHandler { onBack() }
}

@OptIn(ExperimentalTvMaterial3Api::class, UnstableApi::class)
@Composable
private fun TvLivePlayerSurfaceContent(
    controller: PlayerSurfaceController,
    spec: com.insomnia.player.PlaybackSpec,
    onBack: () -> Unit,
) {
    val displayInfo by controller.displayInfoFlow.collectAsState()
    val itemListInfo by controller.itemListInfoFlow.collectAsState()
    val sourceManager by controller.sourceManagerFlow.collectAsState()
    val session = controller.playbackSession
    val surface = rememberPlaybackSurface(spec = spec, session = session)
    PlaybackHostEffects(surface.exo)

    val exo = surface.exo

    var isBuffering by remember { mutableStateOf(false) }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    val infoOverlay = rememberInfoOverlayState(
        instanceKey = spec.sources[spec.state.sourceIndex].url,
        displayInfo = displayInfo,
        session = session,
        spec = spec,
        bandwidthMbps = surface.bandwidthMbps,
    )

    val channelList = rememberChannelListOverlayState(
        channels = itemListInfo?.names ?: emptyList(),
        current = itemListInfo?.current ?: 0,
    )

    fun switchSource(delta: Int) {
        val mgr = sourceManager ?: return
        mgr.selectSource(mgr.selectedIndex + delta)
    }

    BackHandler {
        when {
            channelList.isOpen -> channelList.close()
            infoOverlay.isVisible -> infoOverlay.hide()
            else -> { surface.leaveSurface(); onBack() }
        }
    }

    var consumedDown by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        TvPlayerView(
            player = exo,
            session = session,
            modifier = Modifier.fillMaxSize(),
            onOpenMenu = { channelList.open() },
            onBack = {
                when {
                    channelList.isOpen -> channelList.close()
                    infoOverlay.isVisible -> infoOverlay.hide()
                    else -> { surface.leaveSurface(); onBack() }
                }
            },
            onKey = { event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    if (consumedDown && event.action == KeyEvent.ACTION_UP) {
                        consumedDown = false
                        return@TvPlayerView true
                    }
                    return@TvPlayerView false
                }
                consumedDown = true
                if (channelList.isOpen) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> channelList.navigate(dRow = -1, dCol = 0)
                        KeyEvent.KEYCODE_DPAD_DOWN -> channelList.navigate(dRow = 1, dCol = 0)
                        KeyEvent.KEYCODE_DPAD_LEFT -> channelList.navigate(dRow = 0, dCol = -1)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> channelList.navigate(dRow = 0, dCol = 1)
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            controller.requestSwitchItem(channelList.cursor)
                            channelList.close()
                        }
                        KeyEvent.KEYCODE_BACK -> channelList.close()
                        else -> consumedDown = false
                    }
                    return@TvPlayerView true
                }
                val current = itemListInfo?.current ?: 0
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> controller.requestSwitchItem(current - 1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> controller.requestSwitchItem(current + 1)
                    KeyEvent.KEYCODE_DPAD_LEFT -> switchSource(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> switchSource(1)
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_SPACE,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (infoOverlay.isVisible) infoOverlay.hide() else infoOverlay.show()
                    }
                    else -> consumedDown = false
                }
                consumedDown
            },
        )

        AnimatedVisibility(
            visible = isBuffering,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                text = "buffering...",
                modifier = Modifier
                    .background(ScrimMedium, shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        InfoOverlay(infoOverlay)
        ChannelListOverlay(channelList, onSelect = { controller.requestSwitchItem(it) })
    }
}
