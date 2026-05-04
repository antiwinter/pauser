package com.opentune.player

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.PlayerView.ControllerVisibilityListener
import androidx.media3.ui.R as Media3UiR

/**
 * TV: Media3 [PlayerView.dispatchKeyEvent] consumes all DPAD keys when the controller is hidden
 * (shows overlay, returns true, never calls super), so [setOnKeyListener] never runs. This subclass
 * handles play/pause and ±15s seek on that path, and moves focus into the control strip when the
 * overlay opens (stock focuses play/pause, which we removed from the layout).
 */
@UnstableApi
class OpenTuneTvPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PlayerView(context, attrs, defStyleAttr) {

    private var useControllerFlag: Boolean = true

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setControllerVisibilityListener(
            object : ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    Log.d(LOG_TAG, "controllerVisibility=$visibility")
                    if (visibility == View.VISIBLE) {
                        post { requestFocusOnControllerChrome() }
                    }
                }
            },
        )
    }

    override fun setUseController(useController: Boolean) {
        useControllerFlag = useController
        super.setUseController(useController)
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
        val isDpad = isDpadKey(event.keyCode)
        if (isDpad && useControllerFlag && !isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (p != null && event.action == KeyEvent.ACTION_DOWN) {
                        val pos = p.currentPosition - SEEK_MS
                        p.seekTo(pos.coerceAtLeast(0L))
                        Log.d(LOG_TAG, "seek -${SEEK_MS}ms -> ${p.currentPosition}")
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (p != null && event.action == KeyEvent.ACTION_DOWN) {
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
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    if (p != null && event.action == KeyEvent.ACTION_DOWN) {
                        if (p.isPlaying) {
                            p.pause()
                            Log.d(LOG_TAG, "pause()")
                        } else {
                            p.play()
                            Log.d(LOG_TAG, "play() playWhenReady=${p.playWhenReady}")
                        }
                    }
                    return true
                }
                else -> {
                    showController()
                    Log.d(LOG_TAG, "showController() for keyCode=${event.keyCode}")
                    return true
                }
            }
        }
        val consumed = super.dispatchKeyEvent(event)
        if (Log.isLoggable(LOG_TAG, Log.VERBOSE)) {
            Log.v(LOG_TAG, "super.dispatchKeyEvent -> $consumed")
        }
        return consumed
    }

    private fun requestFocusOnControllerChrome() {
        val basic = findViewById<ViewGroup>(Media3UiR.id.exo_basic_controls)
        if (basic == null) {
            Log.w(LOG_TAG, "requestFocusOnControllerChrome: exo_basic_controls missing")
            return
        }
        for (i in basic.childCount - 1 downTo 0) {
            val child = basic.getChildAt(i)
            if (child.visibility == View.VISIBLE && child.isFocusable && child.isShown) {
                val ok = child.requestFocus()
                Log.d(
                    LOG_TAG,
                    "requestFocusOnControllerChrome child=${child.javaClass.simpleName} ok=$ok " +
                        "focused=${findFocus()?.javaClass?.simpleName}",
                )
                return
            }
        }
        Log.w(LOG_TAG, "requestFocusOnControllerChrome: no visible focusable child in bottom bar")
    }

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
