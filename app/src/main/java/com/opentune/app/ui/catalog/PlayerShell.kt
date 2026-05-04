package com.opentune.app.ui.catalog

import androidx.compose.runtime.Composable

/** Shared container for player routes; extend when common chrome (errors, loading) grows. */
@Composable
fun PlayerShell(content: @Composable () -> Unit) {
    content()
}
