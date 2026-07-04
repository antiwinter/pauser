package com.insomnia.player.manager.subtitle

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import com.insomnia.player.PlaybackState
import com.insomnia.player.R
import com.insomnia.player.engine.PlaybackSession
import timber.log.Timber

/**
 * Owns the live subtitle-adjust display state (vertical offset, scale, lock flag, current cue
 * text) plus the key dispatch that mutates it while adjust mode is on. The cue is rendered by
 * [CCOverlay] (a TextView hosted in Compose), not media3's `SubtitleView` — so the offset/scale
 * are applied by the overlay reading this state, and `SubtitleView` is hidden entirely for
 * adjustable text tracks. State is plain snapshot state, so composables reading it recompose
 * without a Compose scope to construct the owner.
 */
@UnstableApi
internal class CC(
    private val session: PlaybackSession,
) {
    private var offsetFraction by mutableFloatStateOf(0f)
    private var sizeScaleValue by mutableFloatStateOf(1f)
    private var locked by mutableStateOf(true)
    private var screenHeight by mutableFloatStateOf(0f)
    private var cueTextValue by mutableStateOf<CharSequence?>(null)

    val translationYPx: Float get() = offsetFraction * screenHeight
    val sizeScale: Float get() = sizeScaleValue
    val isLocked: Boolean get() = locked
    val cue: CharSequence? get() = cueTextValue
    val screenHeightPx: Float get() = screenHeight

    // True when the active subtitle is a text track the CC overlay renders (vs. media3's
    // SubtitleView for abs-pos PGS/ASS). Compose state so the menu's Adjust entry and the
    // SubtitleView visibility recompose when it flips; set by SubtitleManager.onTracksChanged.
    internal var isActive by mutableStateOf(false)

    /** Fed by the surface from the current screen metrics; drives [translationYPx] and [offsetStep]. */
    fun setScreenHeightPx(px: Float) { screenHeight = px }

    /** Fed by SubtitleManager.onCues; the live cue text (a Spanned for SRT/VTT, rendered natively
     *  by the overlay's TextView). Null/blank when no cue is active. */
    fun setCueText(text: CharSequence?) { cueTextValue = text }

    /** Enters adjust mode: the overlay shows the backdrop + sample/cue and consumes DPAD keys. */
    fun unlock() { locked = false }

    /** Restores saved offset/scale from the entry state, clears the cue, re-locks, and refreshes
     *  the SubtitleView visibility toggle. */
    fun reset(state: PlaybackState? = null) {
        offsetFraction = state?.subtitleOffsetFraction ?: 0f
        sizeScaleValue = state?.subtitleSizeScale ?: 1f
        locked = true
        cueTextValue = null
        refresh()
    }

    /** Toggles media3's `SubtitleView` off for adjustable text tracks (the OSD renders the cue) and
     *  on for abs-pos tracks (PGS/VobSub/DVB/ASS) where authored positioning must stand. */
    internal fun refresh() {
        session.view?.subtitleView?.visibility =
            if (isActive) View.GONE else View.VISIBLE
    }

    private val offsetStep: Float get() =
        if (screenHeight > 0f) 20f / screenHeight else 0f

    fun adjustOffsetUp() { offsetFraction += offsetStep }
    fun adjustOffsetDown() { offsetFraction -= offsetStep }

    fun adjustScaleDown() {
        sizeScaleValue = (sizeScaleValue - 0.1f).coerceAtLeast(0.3f)
    }

    fun adjustScaleUp() {
        sizeScaleValue = (sizeScaleValue + 0.1f).coerceAtMost(3f)
    }

    fun confirm() {
        locked = true
        Timber.d("confirmAdjust: offset=$offsetFraction scale=$sizeScaleValue")
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

/**
 * The sole subtitle overlay. Renders the live cue text (when present) and the adjust sample (when
 * in adjust mode with no live cue) via a [TextView] so media3's `Spanned` cues (italic/bold/
 * underline/color from SRT/VTT inline tags) render natively with no span-mapping. Center-anchored:
 * the TextView is wrapped in a Compose `Box` aligned `Center` and shifted by `translationY` so its
 * vertical center tracks the anchor — a 1-row cue sits on the anchor, a 2-row cue's gap sits on
 * the anchor. Scale grows symmetrically about the same center.
 *
 * Render matrix (unlocked = adjust mode on):
 *   unlocked && cue  → backdrop + cue
 *   !unlocked && cue → cue
 *   unlocked && !cue → backdrop + sample
 *   !unlocked && !cue→ nothing
 */
@Composable
@UnstableApi
internal fun CCOverlay(cc: CC) {
    val cueText = cc.cue
    if (cc.isLocked && cueText.isNullOrBlank()) return
    val sample = stringResource(R.string.subtitle_adjust_sample)
    val text: CharSequence? = cueText ?: if (!cc.isLocked) sample else null
    if (text == null) return

    val screenHeight = cc.screenHeightPx
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val hPadPx = with(density) { 28.dp.toPx() }
    val maxWidthPx = (with(density) { configuration.screenWidthDp.dp.toPx() } * 0.9f - 2 * hPadPx)
        .toInt().coerceAtLeast(0)

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    if (screenHeight > 0f) {
                        translationY = screenHeight / 2f - 0.08f * screenHeight - cc.translationYPx
                    }
                    scaleX = cc.sizeScale
                    scaleY = cc.sizeScale
                }
                .background(
                    if (cc.isLocked) Color.Transparent else Color.White.copy(alpha = 0.15f),
                    RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 28.dp, vertical = 8.dp),
        ) {
            AndroidView(
                factory = { ctx ->
                    TextView(ctx).apply {
                        gravity = Gravity.CENTER
                        setTextColor(android.graphics.Color.WHITE)
                        paint.setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
                        setMaxWidth(maxWidthPx)
                    }
                },
                update = { tv -> tv.text = text },
            )
        }
    }
}
