package com.opentune.player.manager.subtitle

import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView

@UnstableApi
internal fun applySubtitleStyle(view: PlayerView, translationYPx: Float, sizeScale: Float) {
    val sv = view.subtitleView
    if (sv == null) {
        Log.w("OT_Subtitle", "applySubtitleStyle: subtitleView is null")
        return
    }
    Log.d("OT_Subtitle", "applySubtitleStyle: translationY=$translationYPx sizeScale=$sizeScale")
    sv.scaleX = sizeScale
    sv.scaleY = sizeScale
    sv.setStyle(
        CaptionStyleCompat(
            CaptionStyleCompat.DEFAULT.foregroundColor,
            AndroidColor.TRANSPARENT,
            AndroidColor.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            AndroidColor.BLACK,
            null,
        ),
    )
    val hPad = (16 * view.resources.displayMetrics.density).toInt()
    sv.setPadding(hPad, 0, hPad, 0)
    (sv.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin =
        translationYPx.toInt().coerceAtLeast(0)
    sv.requestLayout()
    sv.invalidate()
}

// Restore exo defaults so abspos subtitles (PGS/VobSub/DVB/ASS) render at authored position/size, clear of any offset/scale/padding a prior text track left on the view.
@UnstableApi
internal fun resetSubtitleStyle(view: PlayerView) {
    val sv = view.subtitleView ?: return
    sv.scaleX = 1f
    sv.scaleY = 1f
    sv.setStyle(CaptionStyleCompat.DEFAULT)
    sv.setPadding(0, 0, 0, 0)
    (sv.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin = 0
    sv.requestLayout()
    sv.invalidate()
}
