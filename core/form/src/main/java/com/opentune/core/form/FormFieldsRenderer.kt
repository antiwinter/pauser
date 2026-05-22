package com.opentune.core.form
import com.opentune.content.contract.FormFieldKind
import com.opentune.content.contract.FormFieldSpec
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = values[spec.id] ?: "",
            onValueChange = { nv -> onValueChange(spec.id, nv) },
            label = { Text(formFieldLabel(spec.labelKey)) },
            placeholder = formFieldPlaceholder(spec.placeholderKey)?.let { ph -> { Text(ph) } },
            singleLine = spec.kind != FormFieldKind.Text,
            visualTransformation = if (spec.kind == FormFieldKind.Password)
                PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (spec.kind == FormFieldKind.Password)
                KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            enabled = enabled,
        )
    }
}
