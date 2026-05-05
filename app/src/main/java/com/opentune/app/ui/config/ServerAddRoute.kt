package com.opentune.app.ui.config

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.R
import com.opentune.app.providers.ServerConfigRepository
import com.opentune.emby.api.EmbyProvider
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
    providerType: String,
    onDone: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OpenTuneApplication
    val fields = remember(providerType) {
        app.providerRegistry.provider(providerType).getFieldsSpec().sortedBy { it.order }
    }
    var values by remember {
        mutableStateOf(fields.associate { it.id to "" })
    }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(providerType) {
        val initial = ServerConfigRepository.loadAddDraft(providerType, app)
        values = fields.associate { it.id to (initial[it.id] ?: "") }
    }

    LaunchedEffect(providerType, fields) {
        snapshotFlow { values }
            .distinctUntilChanged()
            .debounce(600)
            .collect { v ->
                withContext(Dispatchers.IO) {
                    ServerConfigRepository.saveAddDraft(providerType, app, v)
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
                )
            }
            error?.let { Text("Error: $it") }
        }
        Button(
            onClick = {
                scope.launch {
                    error = null
                    val result = withContext(Dispatchers.IO) {
                        ServerConfigRepository.submitAdd(providerType, values, app)
                    }
                    when (result) {
                        is SubmitResult.Success -> {
                            withContext(Dispatchers.IO) {
                                ServerConfigRepository.clearAddDraft(providerType, app)
                            }
                            onDone()
                        }
                        is SubmitResult.Error -> {
                            Log.e(LOG_TAG, "submitAdd failed: ${result.message}")
                            error = result.message
                        }
                    }
                }
            },
        ) {
            Text(
                if (providerType == EmbyProvider.PROVIDER_TYPE) {
                    stringResource(R.string.server_add_primary_http)
                } else {
                    stringResource(R.string.server_add_primary_file_share)
                },
            )
        }
        Button(onClick = onDone) { Text(stringResource(R.string.action_cancel)) }
    }
}
