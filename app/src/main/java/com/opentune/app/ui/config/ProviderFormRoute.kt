package com.opentune.app.ui.config

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.opentune.app.providers.EndpointConfigRepository
import com.opentune.app.providers.ProxyConfigRepository
import com.opentune.app.providers.SubmitResult
import com.opentune.provider.ProviderFieldKind
import com.opentune.storage.ProxyConfigEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "ProviderFormRoute"

enum class FormEntityType { ENDPOINT, PROXY }

@OptIn(ExperimentalTvMaterial3Api::class, FlowPreview::class)
@Composable
fun ProviderFormRoute(
    entityType: FormEntityType,
    protocol: String,
    existingId: String? = null,
    onDone: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OpenTuneApplication
    val isAdd = existingId == null

    val fields = remember(entityType, protocol) {
        when (entityType) {
            FormEntityType.ENDPOINT -> app.providerRegistry.provider(protocol).getFieldsSpec().sortedBy { it.order }
            FormEntityType.PROXY -> app.proxyProviderRegistry.proxy(protocol).getFieldsSpec().sortedBy { it.order }
        }
    }
    val supportsProxy = remember(entityType, protocol) {
        entityType == FormEntityType.ENDPOINT &&
            app.providerRegistry.provider(protocol).supportsProxy
    }

    var values by remember { mutableStateOf(fields.associate { it.id to "" }) }
    var selectedProxyConfigId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(isAdd) }
    val scope = rememberCoroutineScope()

    val proxies by app.storageBindings.proxyConfigDao.observeAll()
        .collectAsState(initial = emptyList())
    val enabledProxies = proxies.filter { it.isEnabled }

    LaunchedEffect(entityType, protocol, existingId) {
        if (isAdd && entityType == FormEntityType.ENDPOINT) {
            val draft = EndpointConfigRepository.loadAddDraft(protocol, app)
            values = fields.associate { it.id to (draft[it.id] ?: "") }
        } else if (!isAdd) {
            loaded = false
            val initial = withContext(Dispatchers.IO) {
                when (entityType) {
                    FormEntityType.ENDPOINT -> EndpointConfigRepository.loadEditFields(protocol, app, existingId!!)
                    FormEntityType.PROXY -> ProxyConfigRepository.loadEditFields(protocol, app, existingId!!)
                }
            }
            values = fields.associate { it.id to (initial[it.id] ?: "") }
            if (supportsProxy) {
                selectedProxyConfigId = withContext(Dispatchers.IO) {
                    EndpointConfigRepository.loadEditProxyConfigId(existingId!!, app)
                }
            }
            loaded = true
        }
    }

    if (isAdd && entityType == FormEntityType.ENDPOINT) {
        LaunchedEffect(protocol, fields) {
            snapshotFlow { values }
                .distinctUntilChanged()
                .debounce(600)
                .collect { v ->
                    withContext(Dispatchers.IO) {
                        EndpointConfigRepository.saveAddDraft(protocol, app, v)
                    }
                }
        }
    }

    val titleRes = when {
        entityType == FormEntityType.PROXY && isAdd -> R.string.proxy_add_title
        entityType == FormEntityType.PROXY && !isAdd -> R.string.proxy_edit_title
        isAdd -> R.string.endpoint_add_title
        else -> R.string.endpoint_edit_title
    }
    val primaryRes = when {
        entityType == FormEntityType.PROXY && isAdd -> R.string.proxy_add_primary
        entityType == FormEntityType.PROXY && !isAdd -> R.string.proxy_edit_primary
        isAdd -> R.string.endpoint_add_primary
        else -> R.string.endpoint_edit_primary
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
            Text(stringResource(titleRes))
            if (isAdd && entityType == FormEntityType.ENDPOINT) {
                Text(stringResource(R.string.endpoint_add_hint_autosave))
            }

            if (!loaded) {
                Text("Loading…")
            } else {
                fields.forEach { spec ->
                    OutlinedTextField(
                        value = values[spec.id] ?: "",
                        onValueChange = { nv -> values = values + (spec.id to nv) },
                        label = { Text(providerFieldLabel(spec.labelKey)) },
                        placeholder = providerFieldPlaceholder(spec.placeholderKey)
                            ?.let { ph -> { Text(ph) } },
                        singleLine = spec.kind != ProviderFieldKind.Text,
                        visualTransformation = if (spec.kind == ProviderFieldKind.Password)
                            PasswordVisualTransformation() else VisualTransformation.None,
                        enabled = !isLoading,
                    )
                }

                if (supportsProxy) {
                    ProxySelector(
                        proxies = enabledProxies,
                        selectedId = selectedProxyConfigId,
                        onSelect = { selectedProxyConfigId = it },
                        enabled = !isLoading,
                    )
                }
            }

            error?.let {
                Text("Error: $it", color = MaterialTheme.colorScheme.error)
            }
        }

        Button(
            onClick = {
                if (isLoading) return@Button
                scope.launch {
                    error = null
                    isLoading = true
                    try {
                        val result: SubmitResult = withContext(Dispatchers.IO) {
                            when {
                                entityType == FormEntityType.ENDPOINT && isAdd ->
                                    EndpointConfigRepository.submitAdd(protocol, values, selectedProxyConfigId, app)
                                entityType == FormEntityType.ENDPOINT && !isAdd ->
                                    EndpointConfigRepository.submitEdit(protocol, existingId!!, values, selectedProxyConfigId, app)
                                entityType == FormEntityType.PROXY && isAdd ->
                                    ProxyConfigRepository.submitAdd(protocol, values, app)
                                else ->
                                    ProxyConfigRepository.submitEdit(protocol, existingId!!, values, app)
                            }
                        }
                        when (result) {
                            is SubmitResult.Success -> {
                                if (isAdd && entityType == FormEntityType.ENDPOINT) {
                                    withContext(Dispatchers.IO) {
                                        EndpointConfigRepository.clearAddDraft(protocol, app)
                                    }
                                }
                                onDone()
                            }
                            is SubmitResult.Error -> {
                                Log.e(LOG_TAG, "submit failed: ${result.message}")
                                error = result.message
                            }
                        }
                    } finally {
                        isLoading = false
                    }
                }
            },
            enabled = !isLoading && loaded,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(primaryRes))
            }
        }
        Button(onClick = onDone, enabled = !isLoading) { Text(stringResource(R.string.action_cancel)) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProxySelector(
    proxies: List<ProxyConfigEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = proxies.find { it.id == selectedId }?.displayName
        ?: stringResource(R.string.proxy_none)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.proxy_section_title), style = MaterialTheme.typography.labelMedium)
        Box {
            Button(onClick = { expanded = true }, enabled = enabled) {
                Text(selectedLabel)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.proxy_none)) },
                    onClick = { onSelect(null); expanded = false },
                )
                proxies.forEach { proxy ->
                    DropdownMenuItem(
                        text = { Text(proxy.displayName) },
                        onClick = { onSelect(proxy.id); expanded = false },
                    )
                }
            }
        }
    }
}
