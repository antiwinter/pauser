package com.insomnia.content.ui.catalog.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api

enum class SearchScope { Global, Current }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchModal(
    onDismiss: () -> Unit,
    onConfirm: (term: String, scope: SearchScope) -> Unit,
) {
    var term by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(SearchScope.Current) }
    val fieldFocusRequester = remember { FocusRequester() }

    // Hosted in a real Dialog window so the background screen (BrowseScreen) is no
    // longer the focused window and cannot receive key events: D-pad directional
    // moves and Back stay inside the dialog instead of leaking to the grid behind.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        LaunchedEffect(Unit) { fieldFocusRequester.requestFocus() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .background(Color(0xFF1E1E1E))
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                M3Text("Search", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocusRequester),
                    label = { M3Text("Search term") },
                    singleLine = true,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { scope = SearchScope.Global },
                        modifier = if (scope == SearchScope.Global)
                            Modifier.background(Color(0xFF3F51B5)) else Modifier,
                    ) { M3Text("Global") }
                    Button(
                        onClick = { scope = SearchScope.Current },
                        modifier = if (scope == SearchScope.Current)
                            Modifier.background(Color(0xFF3F51B5)) else Modifier,
                    ) { M3Text("Current location") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    Button(onClick = onDismiss) { M3Text("Cancel") }
                    Button(
                        onClick = {
                            val trimmed = term.trim()
                            if (trimmed.isNotEmpty()) onConfirm(trimmed, scope)
                        },
                    ) { M3Text("Search") }
                }
            }
        }
    }
}
