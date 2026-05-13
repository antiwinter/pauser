package com.opentune.app.ui.config

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.R
import com.opentune.app.providers.ServerConfigRepository
import com.opentune.provider.ServerFieldKind
import com.opentune.provider.SubmitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OpenTuneServerAdd"

@OptIn(ExperimentalTvMaterial3Api::class, FlowPreview::class)
@Composable
fun ServerAddRoute(
    protocol: String,
    onDone: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OpenTuneApplication
    val fields = remember(protocol) {
        app.providerRegistry.provider(protocol).getFieldsSpec().sortedBy { it.order }
    }
    var values by remember {
        mutableStateOf(fields.associate { it.id to "" })
    }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(protocol) {
        val initial = ServerConfigRepository.loadAddDraft(protocol, app)
        values = fields.associate { it.id to (initial[it.id] ?: "") }
    }

    LaunchedEffect(protocol, fields) {
        snapshotFlow { values }
            .distinctUntilChanged()
            .debounce(600)
            .collect { v ->
                withContext(Dispatchers.IO) {
                    ServerConfigRepository.saveAddDraft(protocol, app, v)
                }
            }
    }

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.server_add_title))
            Text(stringResource(R.string.server_add_hint_autosave))
            fields.forEach { spec ->
                val v = values[spec.id] ?: ""
                OutlinedTextField(
                    value = v,
                    onValueChange = { nv -> values = values + (spec.id to nv) },
                    label = { Text(providerFieldLabel(spec.labelKey)) },
                    placeholder = run {
                        val ph = providerFieldPlaceholder(spec.placeholderKey)
                        if (ph != null) ({ Text(ph) }) else null
                    },
                    singleLine = spec.kind != ServerFieldKind.Text,
                    visualTransformation = if (spec.kind == ServerFieldKind.Password) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    enabled = !isLoading,
                )
            }
        }
        Button(
            onClick = {
                if (isLoading) return@Button
                scope.launch {
                    error = null
                    isLoading = true
                    try {
                        val result = withContext(Dispatchers.IO) {
                            ServerConfigRepository.submitAdd(protocol, values, app)
                        }
                        when (result) {
                            is SubmitResult.Success -> {
                                withContext(Dispatchers.IO) {
                                    ServerConfigRepository.clearAddDraft(protocol, app)
                                }
                                onDone()
                            }
                            is SubmitResult.Error -> {
                                Log.e(LOG_TAG, "submitAdd failed: ${result.message}")
                                error = result.message
                            }
                        }
                    } finally {
                        isLoading = false
                    }
                }
            },
            enabled = !isLoading,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(stringResource(R.string.server_add_primary))
            }
        }
        error?.let {
            Text(
                text = "Error: $it",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onDone, enabled = !isLoading) { Text(stringResource(R.string.action_cancel)) }
    }
}
