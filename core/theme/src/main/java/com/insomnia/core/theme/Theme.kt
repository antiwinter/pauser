@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.insomnia.core.theme

import androidx.compose.material3.darkColorScheme as composeDarkColorScheme
import androidx.compose.material3.MaterialTheme as ComposeMaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme

/**
 * Insomnia dark UI palette.
 *
 * Five chrome colors drive every UI surface through `MaterialTheme.colorScheme`:
 *
 *   Background      true black      player surface, image viewer, app canvas
 *   Surface         elevated panel   menus, cards, controller bars
 *   OnSurface       primary text     titles, icons, values  (via `onSurface`)
 *   Muted           secondary text   version label, captions (via `onSurfaceVariant`)
 *   Accent          blue             focus indicator, play head, selected state (via `primary`)
 *
 * Plus three semi-transparent scrim overlays for legibility over arbitrary content
 * (a video frame, movie poster) and the [Status] palette below.
 */

private val Background      = Color(0xFF000000)  // true black
private val Surface         = Color(0xFF1C1C1C)  // elevated panel
private val SurfaceVariant  = Color(0xFF2A2A2A)  // skeletons / placeholders
private val OnSurface       = Color(0xFFF5F5F5)  // primary text/icons
private val Muted           = Color(0xFFAAAAAA)  // secondary (muted) text
private val Accent          = Color(0xFF2979FF)  // blue — focus / play head / selected
private val OnAccent        = Color(0xFFFFFFFF)
private val Fail            = Color(0xFFE53935)  // semantic error/favorite red

/** Scrim blacks for content overlays (over video frames / posters, not chrome). */
val ScrimStrong = Color(0xCC000000)   // ~80% — OSD / controller / info bar backings
val ScrimMedium = Color(0x88000000)   // ~53% — transient toast backings
val ScrimChip   = Color(0x8C000000)   // ~55% — text legibility chip on thumbnails / video frames

/** Status colors — data-viz only (codec health, latency tiers, favorite/rating glyphs). */
val StatusOk      = Color(0xFF4CAF50)
val StatusWarn    = Color(0xFFF9A825)
val StatusFail    = Color(0xFFE53935)
val StatusSlow    = Color(0xFFE65100)
val StatusUnknown = Color(0xFF808080)

// Both Compose Material 3 and TV Material 3 have their own MaterialTheme
// with separate CompositionLocals. Both must be set for colors to resolve
// in every module (content/ui uses tv.material3, player uses compose.material3).

private val InsomniaComposeScheme = composeDarkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = Accent,
    onPrimaryContainer = OnAccent,
    background = Background,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Muted,
    surfaceTint = Accent,
    error = Fail,
    onError = OnAccent,
    errorContainer = Fail,
    onErrorContainer = OnAccent,
    outline = Muted,
    outlineVariant = SurfaceVariant,
    scrim = Color.Black,
)

private val InsomniaTvScheme = tvDarkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = Accent,
    onPrimaryContainer = OnAccent,
    background = Background,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Muted,
    surfaceTint = Accent,
    error = Fail,
    onError = OnAccent,
    errorContainer = Fail,
    onErrorContainer = OnAccent,
    border = Muted,
    borderVariant = SurfaceVariant,
    scrim = Color.Black,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun InsomniaTheme(content: @Composable () -> Unit) {
    ComposeMaterialTheme(
        colorScheme = InsomniaComposeScheme,
    ) {
        TvMaterialTheme(
            colorScheme = InsomniaTvScheme,
        ) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides InsomniaComposeScheme.onSurfaceVariant,
                androidx.tv.material3.LocalContentColor provides InsomniaTvScheme.onSurfaceVariant,
                content = content
            )
        }
    }
}