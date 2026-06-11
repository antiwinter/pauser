package com.opentune.core.form
import com.opentune.core.form.contract.FormFieldKind
import com.opentune.core.form.contract.FormFieldSpec
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

// Keys that should move focus instead of activating the input field
private val navigationKeys = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.Tab, Key.Back
)

// Check if a key is a printable character key (not navigation, not enter)
private fun isCharacterKey(key: Key): Boolean {
    return key !in navigationKeys && key != Key.DirectionCenter && key != Key.Enter
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FormFieldsRenderer(
    fields: List<FormFieldSpec>,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit,
    enabled: Boolean = true,
) {
    val focusManager = LocalFocusManager.current

    fields.forEach { spec ->
        var editing by remember(spec.id) { mutableStateOf(false) }
        var focused by remember(spec.id) { mutableStateOf(false) }

        OutlinedTextField(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) editing = false
                }
                .onPreviewKeyEvent { event ->
                    // Navigation keys: move focus on KeyDown and consume to prevent default
                    if (event.key in navigationKeys && event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                            Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                            else -> {}
                        }
                        true // consume to prevent default navigation
                    }
                    // Character key: activate editing and let TextField handle input
                    else if (!editing && event.type == KeyEventType.KeyDown && isCharacterKey(event.key)) {
                        editing = true
                        false // let key pass through for TextField to process
                    }
                    // Enter/Center: activate editing without typing
                    else if (!editing &&
                        event.type == KeyEventType.KeyUp &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter)
                    ) {
                        editing = true
                        true
                    }
                    else false
                },
            value = values[spec.id] ?: spec.defaultValue ?: "",
            onValueChange = { nv -> if (editing) onValueChange(spec.id, nv) },
            label = { Text(formFieldLabel(spec.labelKey)) },
            placeholder = formFieldPlaceholder(spec.placeholderKey)?.let { ph -> { Text(ph) } },
            singleLine = spec.kind != FormFieldKind.Text,
            visualTransformation = if (spec.kind == FormFieldKind.Password)
                PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (spec.kind == FormFieldKind.Password)
                KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            enabled = enabled,
            readOnly = !editing,
            colors = if (focused && !editing)
                OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
                    unfocusedLabelColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer,
                    unfocusedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer,
                )
            else OutlinedTextFieldDefaults.colors(),
        )
    }
}
