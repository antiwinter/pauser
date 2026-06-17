package com.opentune.core.form

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

val navigationKeys = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.Tab, Key.Back,
)

fun isCharacterKey(key: Key): Boolean =
    key !in navigationKeys && key != Key.DirectionCenter && key != Key.Enter

@Composable
fun TvOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    label: @Composable () -> Unit = {},
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }

    OutlinedTextField(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) editing = false
            }
            .onPreviewKeyEvent { event ->
                if (event.key in navigationKeys && event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionUp    -> focusManager.moveFocus(FocusDirection.Up)
                        Key.DirectionDown  -> focusManager.moveFocus(FocusDirection.Down)
                        Key.DirectionLeft  -> focusManager.moveFocus(FocusDirection.Left)
                        Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                        else -> {}
                    }
                    true
                } else if (!editing && event.type == KeyEventType.KeyDown && isCharacterKey(event.key)) {
                    editing = true
                    false
                } else if (!editing &&
                    event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    editing = true
                    true
                } else false
            },
        value = value,
        onValueChange = { if (editing) onValueChange(it) },
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        readOnly = !editing,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        enabled = enabled,
        colors = if (focused && !editing)
            OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
                unfocusedLabelColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer,
                unfocusedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer,
            )
        else OutlinedTextFieldDefaults.colors(),
    )
}
