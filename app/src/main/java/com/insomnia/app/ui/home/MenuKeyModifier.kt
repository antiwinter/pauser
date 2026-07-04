package com.insomnia.app.ui.home

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * Invokes [onMenu] when the TV **Menu** or **Page Down** key is pressed while this node has focus.
 * On the Android Emulator, **Menu** is often bound to **F2** or **Page Up** (see emulator keyboard shortcuts, F1 / Help).
 *
 * The emulator maps the host PAGE_DOWN key to `KEYCODE_UNKNOWN` with scan code 109
 * (Linux `KEY_PAGEDOWN`), so we check that as a fallback.
 */
fun Modifier.onTvMenuKeyDown(onMenu: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    val isMenuKey = native.keyCode in listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PAGE_DOWN)
    val isEmulatorPageDown = native.keyCode == KeyEvent.KEYCODE_UNKNOWN && native.scanCode == 109
    if (native.action == KeyEvent.ACTION_DOWN && (isMenuKey || isEmulatorPageDown)) {
        onMenu()
        true
    } else {
        false
    }
}
