package com.opentune.player.manager.subtitle

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.opentune.player.PlaybackState
import com.opentune.player.R
import com.opentune.player.engine.PlaybackSession

private const val SUB_LOG_TAG = "OT_Subtitle"

/**
 * Owns the live subtitle-adjust display state (vertical offset, scale, active flag) plus the
 * key dispatch that mutates it while adjust mode is on. [SubtitleManager] composes this and
 * delegates its menu "adjust" entry + per-entry reset to it. The state is plain snapshot state,
 * so composables reading it recompose without a Compose scope to construct the owner.
 */
@UnstableApi
internal class SubtitleAdjust(
    private val session: PlaybackSession,
) {
    private var offsetFraction by mutableFloatStateOf(0f)
    private var sizeScaleValue by mutableFloatStateOf(1f)
    private var active by mutableStateOf(false)
    private var screenHeight by mutableFloatStateOf(0f)

    val translationYPx: Float get() = offsetFraction * screenHeight
    val sizeScale: Float get() = sizeScaleValue
    val isActive: Boolean get() = active

    /** Fed by the surface from the current screen metrics; drives [translationYPx] and [offsetStep]. */
    fun setScreenHeightPx(px: Float) { screenHeight = px }

    fun activate() { active = true }

    /** Restores saved offset/scale from the entry state and exits adjust mode, then applies to view. */
    fun reset(state: PlaybackState?) {
        offsetFraction = state?.subtitleOffsetFraction ?: 0f
        sizeScaleValue = state?.subtitleSizeScale ?: 1f
        active = false
        applyStyle()
    }

    internal fun applyStyle() {
        session.view?.let { applySubtitleStyle(it, translationYPx, sizeScaleValue) }
    }

    private val offsetStep: Float get() =
        if (screenHeight > 0f) 20f / screenHeight else 0f

    fun adjustOffsetUp() {
        offsetFraction -= offsetStep
        applyStyle()
    }

    fun adjustOffsetDown() {
        offsetFraction += offsetStep
        applyStyle()
    }

    fun adjustScaleDown() {
        sizeScaleValue = (sizeScaleValue - 0.1f).coerceAtLeast(0.3f)
        applyStyle()
    }

    fun adjustScaleUp() {
        sizeScaleValue = (sizeScaleValue + 0.1f).coerceAtMost(3f)
        applyStyle()
    }

    fun confirm() {
        active = false
        Log.d(SUB_LOG_TAG, "confirmAdjust: offset=$offsetFraction scale=$sizeScaleValue")
        session.updateSubtitlePrefs(offsetFraction, sizeScaleValue)
    }

    /**
     * Handles a DPAD key while adjust mode is on (up/down → offset, left/right → scale,
     * center/enter/numpad/back → confirm). Acts only on ACTION_DOWN. The surface still owns the
     * decision to consume all keys while active.
     */
    fun onKeyEvent(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> adjustOffsetUp()
            KeyEvent.KEYCODE_DPAD_DOWN -> adjustOffsetDown()
            KeyEvent.KEYCODE_DPAD_LEFT -> adjustScaleDown()
            KeyEvent.KEYCODE_DPAD_RIGHT -> adjustScaleUp()
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK -> confirm()
        }
    }
}

@Composable
internal fun SubtitleAdjustOverlay(adjust: SubtitleAdjust) {
    if (!adjust.isActive) return
    val previewBottomDp = with(LocalDensity.current) { adjust.translationYPx.toDp() }
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.subtitle_adjust_sample),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (previewBottomDp + 48.dp).coerceAtLeast(48.dp))
                .graphicsLayer { scaleX = adjust.sizeScale; scaleY = adjust.sizeScale }
                .background(Color.White.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                .padding(horizontal = 28.dp, vertical = 10.dp),
            color = Color.White,
            fontSize = 20.sp,
        )
        Text(
            text = stringResource(R.string.subtitle_adjust_hint),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .background(Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(6.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            color = Color(0xFFAAAAAA),
            fontSize = 13.sp,
        )
    }
}
