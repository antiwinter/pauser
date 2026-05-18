package com.opentune.player.tv

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.opentune.player.R
import com.opentune.player.applySubtitleStyle
import com.opentune.player.configurePlayerViewDefaults

/** Hide controller after this many ms without input. Driven via LaunchedEffect so
 * [PlayerView.hideController] fires immediately with no shrink animation. */
internal const val CONTROLLER_HIDE_AFTER_MS = 5_000

// ---------------------------------------------------------------------------
// OpenTuneTvPlayerView — unchanged behavior, new package
// ---------------------------------------------------------------------------

/**
 * TV: Media3 [PlayerView.dispatchKeyEvent] consumes DPAD when the controller is hidden before
 * [setOnKeyListener] runs. This subclass handles transport keys (±15s seek, play/pause, menu),
 * calls [onTransportKey] so the Compose layer can show the controller bar, and routes overlay
 * interceptors via [onKey].
 *
 * [useController] is set to `false` — the Compose layer renders [PlaybackControllerBar] instead.
 *
 * Focus stays on this [PlayerView] ([ViewGroup.FOCUS_BLOCK_DESCENDANTS]) so TV keys always
 * hit this view.
 */
@UnstableApi
class OpenTuneTvPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PlayerView(context, attrs, defStyleAttr) {

    /** Called when the MENU / PAGE_DOWN key is pressed to open the player menu. */
    var openMenuCallback: (() -> Unit)? = null

    /**
     * Called when BACK is pressed and no overlay is active. The caller decides
     * whether to exit the player.
     */
    var onBack: (() -> Unit)? = null

    /**
     * Notify the Compose layer that a transport key was pressed so it can show the controller bar.
     */
    var onTransportKey: (() -> Unit)? = null

    /**
     * When non-null, all key events are forwarded here first. Return true to consume the event;
     * return false to let it fall through to transport defaults. Receives both ACTION_DOWN and
     * ACTION_UP so overlays can manage their own UP/DOWN lifecycle.
     */
    var onKey: ((KeyEvent) -> Boolean)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        useController = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { requestFocus() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.v(
                LOG_TAG,
                "dispatchKeyEvent keyCode=${event.keyCode} action=${event.action} " +
                    "hasPlayer=${p != null} playing=${p?.isPlaying}",
            )
        }
        if (p != null &&
            p.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) &&
            p.isPlayingAd
        ) {
            return super.dispatchKeyEvent(event)
        }

        // Route to active overlay interceptor (menu, subtitle adjust, etc.)
        val interceptor = onKey
        if (interceptor != null && interceptor(event)) return true

        // Execute side-effects on ACTION_DOWN only.
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // BACK → exit (callers may already handle this via subtitleCtrl.handleBack)
                KeyEvent.KEYCODE_BACK -> onBack?.invoke()

                // MENU / PAGE_DOWN → open player menu
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_PAGE_DOWN,
                -> openMenuCallback?.invoke()

                // LEFT / RIGHT → seek ±15s; show controller bar
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val pos = p?.currentPosition?.minus(SEEK_MS)?.coerceAtLeast(0L) ?: 0L
                    p?.seekTo(pos)
                    Log.d(LOG_TAG, "seek -${SEEK_MS}ms → ${p?.currentPosition}")
                    onTransportKey?.invoke()
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val dur = p?.duration ?: C.TIME_UNSET
                    val to = (p?.currentPosition ?: 0L) + SEEK_MS
                    p?.seekTo(if (dur > 0) to.coerceAtMost(dur) else to)
                    Log.d(LOG_TAG, "seek +${SEEK_MS}ms → ${p?.currentPosition}")
                    onTransportKey?.invoke()
                }

                // CENTER / ENTER / SPACE → play/pause toggle; show controller bar on pause
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                -> {
                    if (p != null) {
                        val wasPlayWhenReady = p.playWhenReady
                        if (wasPlayWhenReady) p.pause() else p.play()
                        Log.d(LOG_TAG, "toggle play/pause playWhenReady=$wasPlayWhenReady")
                        if (wasPlayWhenReady) onTransportKey?.invoke()
                    }
                }

                // MEDIA_PLAY → play (no OSD)
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    p?.play()
                    Log.d(LOG_TAG, "media play")
                }

                // MEDIA_PAUSE / MEDIA_STOP → pause; show controller bar
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP,
                -> {
                    p?.pause()
                    Log.d(LOG_TAG, "media pause/stop")
                    onTransportKey?.invoke()
                }
            }
        }

        // Consume all OWNED_KEYS (both DOWN and UP) so they never reach super.
        if (event.keyCode in OWNED_KEYS) return true

        val consumed = super.dispatchKeyEvent(event)
        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.v(LOG_TAG, "super.dispatchKeyEvent → $consumed")
        }
        return consumed
    }

    private companion object {
        private const val LOG_TAG = "OpenTuneTvPlayerKeys"
        private const val SEEK_MS = 15_000L

        private val OWNED_KEYS = intArrayOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
        ).toHashSet()

        private fun IntArray.toHashSet() = HashSet<Int>().also { s -> forEach { s.add(it) } }
    }
}

// ---------------------------------------------------------------------------
// TvPlayerView — Compose wrapper for OpenTuneTvPlayerView
// ---------------------------------------------------------------------------

@UnstableApi
@Composable
internal fun TvPlayerView(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
    onPlayerViewBound: (PlayerView) -> Unit = {},
    onOpenMenu: () -> Unit = {},
    onBack: () -> Unit = {},
    onTransportKey: () -> Unit = {},
    onKey: ((KeyEvent) -> Boolean)? = null,
    subtitleTranslationYPx: Float = 0f,
    subtitleSizeScale: Float = 1f,
) {
    AndroidView(
        factory = { context ->
            val view = LayoutInflater.from(context)
                .inflate(R.layout.opentune_player_view, null, false) as OpenTuneTvPlayerView
            view.player = player
            configurePlayerViewDefaults(view)
            onPlayerViewBound(view)
            view
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.openMenuCallback = onOpenMenu
            view.onBack = onBack
            view.onTransportKey = onTransportKey
            view.onKey = onKey
            applySubtitleStyle(view, subtitleTranslationYPx, subtitleSizeScale)
        },
        modifier = modifier
            .fillMaxSize()
            .background(ComposeColor.Black),
    )
}
