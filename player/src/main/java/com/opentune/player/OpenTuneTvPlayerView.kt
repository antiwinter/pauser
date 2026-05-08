package com.opentune.player

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupWindow
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.PlayerView.ControllerVisibilityListener
import androidx.media3.ui.R as Media3UiR

/**
 * TV: Media3 [PlayerView.dispatchKeyEvent] consumes DPAD when the controller is hidden before
 * [setOnKeyListener] runs. This subclass handles transport (±15s, play/pause), shows the controller
 * on those actions (5s timeout via [androidx.media3.ui.PlayerView.setControllerShowTimeoutMs]),
 * keeps the same transport when the overlay is visible, opens the menu on MENU / PAGE_DOWN, and
 * exposes [dismissMenuPopupIfShowing] for Back.
 *
 * Focus stays on this [PlayerView] ([ViewGroup.FOCUS_BLOCK_DESCENDANTS]) so TV keys are handled
 * here instead of being stuck on a bottom-bar [ImageButton] that stops receiving events once the
 * controller is hidden.
 */
@UnstableApi
class OpenTuneTvPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PlayerView(context, attrs, defStyleAttr) {

    private var useControllerFlag: Boolean = true
    private var playbackStateIndicatorPlayer: Player? = null
    private var playbackStateIndicatorListener: Player.Listener? = null

    /** Called when the MENU / PAGE_DOWN key is pressed to open the player menu. */
    var openMenuCallback: (() -> Unit)? = null

    /**
     * When non-null, all key events are forwarded here. Return true to consume the event;
     * return false to let it fall through to transport defaults. The callback receives both
     * ACTION_DOWN and ACTION_UP events, but current implementations only respond to ACTION_DOWN.
     * Set to `null` to restore normal transport behaviour.
     */
    var onKey: ((KeyEvent) -> Boolean)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setControllerVisibilityListener(
            object : ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    Log.d(LOG_TAG, "controllerVisibility=$visibility")
                    // Re-take focus whenever the controller shows or hides so keys keep hitting this
                    // view (see class KDoc).
                    post { requestFocus() }
                }
            },
        )
    }

    override fun setUseController(useController: Boolean) {
        useControllerFlag = useController
        super.setUseController(useController)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { requestFocus() }
    }

    override fun onDetachedFromWindow() {
        detachPlaybackStateIndicator()
        super.onDetachedFromWindow()
    }

    /**
     * Keeps `opentune_playback_state` in sync with [player] (play vs pause icon).
     * Safe to call from [OpenTunePlayerView] whenever the bound player instance changes.
     */
    fun updatePlaybackStateIndicatorAttachment() {
        val p = player
        if (p == null) {
            detachPlaybackStateIndicator()
            return
        }
        if (playbackStateIndicatorPlayer === p && playbackStateIndicatorListener != null) {
            refreshPlaybackStateIndicator()
            return
        }
        detachPlaybackStateIndicator()
        playbackStateIndicatorPlayer = p
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    refreshPlaybackStateIndicator()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    refreshPlaybackStateIndicator()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    refreshPlaybackStateIndicator()
                }
            }
        playbackStateIndicatorListener = listener
        p.addListener(listener)
        refreshPlaybackStateIndicator()
    }

    private fun detachPlaybackStateIndicator() {
        playbackStateIndicatorListener?.let { listener ->
            playbackStateIndicatorPlayer?.removeListener(listener)
        }
        playbackStateIndicatorListener = null
        playbackStateIndicatorPlayer = null
    }

    private fun refreshPlaybackStateIndicator() {
        val p = player ?: return
        val image = findViewById<ImageView>(R.id.opentune_playback_state) ?: return
        val showPlay = Util.shouldShowPlayButton(p, /* showPlayButtonIfSuppressed= */ false)
        image.setImageResource(
            if (showPlay) {
                Media3UiR.drawable.exo_styled_controls_play
            } else {
                Media3UiR.drawable.exo_styled_controls_pause
            },
        )
        image.contentDescription =
            resources.getString(
                if (showPlay) {
                    Media3UiR.string.exo_controls_play_description
                } else {
                    Media3UiR.string.exo_controls_pause_description
                },
            )
    }

    /**
     * Dismisses the Exo playback settings [PopupWindow] if it is open. Used so Back closes the menu
     * before hiding the controller or exiting.
     */
    fun dismissMenuPopupIfShowing(): Boolean {
        val control = findViewById<PlayerControlView>(Media3UiR.id.exo_controller) ?: return false
        return try {
            val field = PlayerControlView::class.java.getDeclaredField("settingsWindow")
            field.isAccessible = true
            val popup = field.get(control) as? PopupWindow
            if (popup?.isShowing == true) {
                popup.dismiss()
                post { requestFocus() }
                true
            } else {
                false
            }
        } catch (_: ReflectiveOperationException) {
            false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.v(
                LOG_TAG,
                "dispatchKeyEvent keyCode=${event.keyCode} action=${event.action} " +
                    "overlayVisible=$isControllerFullyVisible useController=$useControllerFlag " +
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

        // Handle all key actions in one unified block
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // MENU / PAGE_DOWN → open player menu
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_PAGE_DOWN,
                -> {
                    if (useControllerFlag) openMenuCallback?.invoke()
                }

                // LEFT / RIGHT → seek
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val pos = p?.currentPosition?.minus(SEEK_MS)?.coerceAtLeast(0L) ?: 0L
                    p?.seekTo(pos)
                    Log.d(LOG_TAG, "seek -${SEEK_MS}ms → ${p?.currentPosition}")
                    showController()
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val dur = p?.duration ?: C.TIME_UNSET
                    val to = (p?.currentPosition ?: 0L) + SEEK_MS
                    p?.seekTo(if (dur > 0) to.coerceAtMost(dur) else to)
                    Log.d(LOG_TAG, "seek +${SEEK_MS}ms → ${p?.currentPosition}")
                    showController()
                }

                // CENTER / ENTER / SPACE → play/pause toggle
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                -> {
                    if (p != null) {
                        if (p.isPlaying) p.pause() else p.play()
                        Log.d(LOG_TAG, "toggle play/pause")
                    }
                    showController()
                }

                // MEDIA_PLAY → play
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    p?.play()
                    Log.d(LOG_TAG, "media play")
                    showController()
                }

                // MEDIA_PAUSE / MEDIA_STOP → pause
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP,
                -> {
                    p?.pause()
                    Log.d(LOG_TAG, "media pause/stop")
                    showController()
                }
            }
            // Keys with side-effects above always consume the event
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
                -> return true
            }
        }

        val consumed = super.dispatchKeyEvent(event)
        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.v(LOG_TAG, "super.dispatchKeyEvent → $consumed")
        }
        return consumed
    }

    private companion object {
        private const val LOG_TAG = "OpenTuneTvPlayerKeys"
        private const val SEEK_MS = 15_000L
    }
}
