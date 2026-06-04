package com.opentune.core.form
import com.opentune.core.form.contract.FormFieldKind
import com.opentune.core.form.contract.FormFieldSpec
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FormFieldsRenderer(
    fields: List<FormFieldSpec>,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit,
    enabled: Boolean = true,
) {
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
                    if (!editing &&
                        event.type == KeyEventType.KeyUp &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter)
                    ) {
                        editing = true
                        true
                    } else false
                }
                .then(if (!editing) Modifier.focusable() else Modifier),
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
