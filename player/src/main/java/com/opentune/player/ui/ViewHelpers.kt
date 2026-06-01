package com.opentune.player.ui

import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView

/** Configure [PlayerView] options that apply to every platform (TV and Pad). */
@UnstableApi
internal fun configurePlayerViewDefaults(view: PlayerView) {
    view.useController = false
    view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
}

/** Apply subtitle scale, style, padding and vertical position to [view]'s SubtitleView. */
@UnstableApi
internal fun applySubtitleStyle(view: PlayerView, translationYPx: Float, sizeScale: Float) {
    val sv = view.subtitleView
    if (sv == null) {
        Log.w("OT_Subtitle", "applySubtitleStyle: subtitleView is null — cannot apply translation/scale")
        return
    }
    Log.d("OT_Subtitle", "applySubtitleStyle: translationY=$translationYPx sizeScale=$sizeScale")
    sv.scaleX = sizeScale
    sv.scaleY = sizeScale
    sv.setStyle(
        CaptionStyleCompat(
            CaptionStyleCompat.DEFAULT.foregroundColor,
            AndroidColor.TRANSPARENT, // no background capsule
            AndroidColor.TRANSPARENT, // no window color
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            AndroidColor.BLACK,
            null, // default typeface
        ),
    )
    val hPad = (16 * view.resources.displayMetrics.density).toInt()
    sv.setPadding(hPad, 0, hPad, 0)
    // Constrain the subtitle view's bottom edge using layout margin.
    // The SubtitleView fills the screen; a bottomMargin shrinks its layout area so
    // the text renders above the target position.
    val layoutParams = sv.layoutParams
    if (layoutParams is android.view.ViewGroup.MarginLayoutParams) {
        layoutParams.bottomMargin = translationYPx.toInt()
    }
    sv.requestLayout()
    sv.invalidate()
}
