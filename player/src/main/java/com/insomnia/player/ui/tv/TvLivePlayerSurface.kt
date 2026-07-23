package com.insomnia.player.ui.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.insomnia.player.PlayerSurfaceController
import com.insomnia.player.ui.BufferingChip
import com.insomnia.player.ui.InfoOverlay
import com.insomnia.player.ui.LoadingOverlay
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
        LoadingOverlay(onBack = onBack)
        return
    }

    val displayInfo by controller.displayInfoFlow.collectAsState()
    val itemListInfo by controller.itemListInfoFlow.collectAsState()
    val sourceManager by controller.sourceManagerFlow.collectAsState()
    val surface = rememberPlaybackSurface(spec = specValue, session = session)
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
        instanceKey = specValue.sources[specValue.state.sourceIndex].url,
        displayInfo = displayInfo,
        session = session,
        spec = specValue,
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

        BufferingChip(
            visible = isBuffering,
            modifier = Modifier.align(Alignment.Center),
        )

        InfoOverlay(infoOverlay)
        ChannelListOverlay(channelList, onSelect = { controller.requestSwitchItem(it) })
    }
}
