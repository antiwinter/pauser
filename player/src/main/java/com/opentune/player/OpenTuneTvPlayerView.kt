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
 * keeps the same transport when the overlay is visible, opens the Exo settings sheet on MENU, and
 * exposes [dismissSettingsPopupIfShowing] for Back.
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

    /** Called when the MENU key is pressed; replaces the native exo settings popup. */
    var settingsMenuCallback: (() -> Unit)? = null

    /**
     * When non-null, all DPAD/CENTER key events are forwarded here instead of triggering
     * transport actions. The screen owns mode-specific routing (overlay nav, subtitle adjust,
     * etc.). Set to `null` to restore normal transport behaviour.
     */
    var onDpadKey: ((keyCode: Int) -> Unit)? = null

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
    fun dismissSettingsPopupIfShowing(): Boolean {
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

        if (useControllerFlag && event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                settingsMenuCallback?.invoke()
                Log.d(LOG_TAG, "MENU -> custom settings")
            }
            return true
        }

        val isDpad = isDpadKey(event.keyCode)

        // Route to caller-defined handler when active (settings overlay, subtitle adjust, etc.)
        val dpadKey = onDpadKey
        if (isDpad && dpadKey != null) {
            if (event.action == KeyEvent.ACTION_DOWN) dpadKey(event.keyCode)
            return true
        }
        if (isDpad && useControllerFlag) {
            if (isTransportDpad(event.keyCode)) {
                if (event.action == KeyEvent.ACTION_DOWN && p != null) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val pos = p.currentPosition - SEEK_MS
                            p.seekTo(pos.coerceAtLeast(0L))
                            Log.d(LOG_TAG, "seek -${SEEK_MS}ms -> ${p.currentPosition}")
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val dur = p.duration
                            val target = p.currentPosition + SEEK_MS
                            val to =
                                if (dur != C.TIME_UNSET && dur > 0) {
                                    target.coerceAtMost(dur)
                                } else {
                                    target
                                }
                            p.seekTo(to)
                            Log.d(LOG_TAG, "seek +${SEEK_MS}ms -> ${p.currentPosition}")
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        -> {
                            if (p.isPlaying) {
                                p.pause()
                                Log.d(LOG_TAG, "pause()")
                            } else {
                                p.play()
                                Log.d(LOG_TAG, "play() playWhenReady=${p.playWhenReady}")
                            }
                        }
                    }
                    showController()
                }
                return true
            }
            if (!isControllerFullyVisible) {
                showController()
                Log.d(LOG_TAG, "showController() for keyCode=${event.keyCode}")
                return true
            }
        }

        val consumed = super.dispatchKeyEvent(event)
        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.v(LOG_TAG, "super.dispatchKeyEvent -> $consumed")
        }
        return consumed
    }

    private fun isTransportDpad(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    @SuppressLint("InlinedApi")
    private fun isDpadKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER

    private companion object {
        private const val LOG_TAG = "OpenTuneTvPlayerKeys"
        private const val SEEK_MS = 15_000L
    }
}
