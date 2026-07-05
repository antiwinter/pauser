package com.insomnia.core.form

import com.insomnia.core.form.contract.FormFieldKind
import com.insomnia.core.form.contract.FormFieldSpec
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Text

@Composable
fun FormFieldsRenderer(
    fields: List<FormFieldSpec>,
    values: Map<String, String>,
    onValueChange: (String, String) -> Unit,
    enabled: Boolean = true,
) {
    fields.forEach { spec ->
        TvOutlinedTextField(
            value = values[spec.id] ?: spec.defaultValue ?: "",
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
