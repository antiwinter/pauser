package com.opentune.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import java.util.UUID

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * Side-effects that any platform's player shell needs but that have nothing to do with
 * the specific input model (DPAD vs touch).
 *
 * - Dismisses any IME left open from a previous screen.
 * - Keeps the screen on while the player is active.
 * - Creates a [MediaSession] so lock-screen / notification controls work.
 */
@UnstableApi
@Composable
internal fun PlaybackHostEffects(exo: ExoPlayer) {
    val context = LocalContext.current
    val rootView = LocalView.current

    // Dismiss any IME left open from a previous screen (e.g. server-add / search text fields).
    // hideSoftInputFromWindow also ends the IME's input connection, preventing it from
    // reappearing when the SurfaceView surface is created on first frame.
    LaunchedEffect(Unit) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(rootView.windowToken, 0)
    }

    DisposableEffect(exo) {
        val activity = context.findActivity()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val session = if (activity != null) {
            MediaSession.Builder(activity, exo)
                .setId(UUID.randomUUID().toString())
                .build()
        } else {
            null
        }
        onDispose {
            session?.release()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
