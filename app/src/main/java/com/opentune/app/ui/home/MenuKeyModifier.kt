package com.opentune.app.ui.home

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * Invokes [onMenu] when the TV **Menu** key is pressed while this node has focus.
 * On the Android Emulator, **Menu** is often bound to **F2** or **Page Up** (see emulator keyboard shortcuts, F1 / Help).
 */
fun Modifier.onTvMenuKeyDown(onMenu: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (native.action == KeyEvent.ACTION_DOWN && native.keyCode == KeyEvent.KEYCODE_MENU) {
        onMenu()
        true
    } else {
        false
    }
}
