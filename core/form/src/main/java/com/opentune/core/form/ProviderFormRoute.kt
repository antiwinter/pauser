package com.opentune.core.form

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.opentune.content.contract.FormFieldKind
import com.opentune.content.contract.FormFieldSpec
import com.opentune.storage.ProxyEntity
import com.opentune.storage.StorageBindingsHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "ProviderFormRoute"

@OptIn(ExperimentalTvMaterial3Api::class, FlowPreview::class)
@Composable
fun ProviderFormRoute(
    fields: List<FormFieldSpec>,
    onLoad: (suspend () -> Pair<Map<String, String>, String?>)? = null,
    onDraftSave: (suspend (Map<String, String>) -> Unit)? = null,
    onSubmit: suspend (values: Map<String, String>, proxyId: String?) -> SubmitResult,
    onDone: () -> Unit,
) {
    val sortedFields = remember(fields) { fields.sortedBy { it.order } }
    val inputFields = remember(sortedFields) { sortedFields.filter { it.kind != FormFieldKind.ProxySelector } }
    val hasProxySelector = remember(sortedFields) { sortedFields.any { it.kind == FormFieldKind.ProxySelector } }

    var values by remember { mutableStateOf(inputFields.associate { it.id to "" }) }
    var selectedProxyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(onLoad == null) }
    val scope = rememberCoroutineScope()

    val proxies by StorageBindingsHolder.get().proxyDao.observeAll()
        .collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        if (onLoad != null) {
            loaded = false
            val (initial, proxyId) = withContext(Dispatchers.IO) { onLoad() }
            values = inputFields.associate { it.id to (initial[it.id] ?: "") }
            selectedProxyId = proxyId
            loaded = true
        }
    }

    if (onDraftSave != null) {
        LaunchedEffect(inputFields) {
            snapshotFlow { values }
                .distinctUntilChanged()
                .debounce(600)
                .collect { v -> withContext(Dispatchers.IO) { onDraftSave(v) } }
        }
    }

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.form_title))
            if (!loaded) {
                Text(stringResource(R.string.form_loading))
            } else {
                FormFieldsRenderer(
                    fields = inputFields,
                    values = values,
                    onValueChange = { id, nv -> values = values + (id to nv) },
                    enabled = !isLoading,
                )
                if (hasProxySelector) {
                    ProxySelector(
                        proxies = proxies,
                        selectedId = selectedProxyId,
                        onSelect = { selectedProxyId = it },
                        enabled = !isLoading,
                    )
                }
            }
            error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
        }

        Button(
            onClick = {
                if (isLoading) return@Button
                scope.launch {
                    error = null
                    isLoading = true
                    try {
                        when (val result = withContext(Dispatchers.IO) { onSubmit(values, selectedProxyId) }) {
                            is SubmitResult.Success -> onDone()
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
                Text(stringResource(R.string.form_button))
            }
        }
        Button(onClick = onDone, enabled = !isLoading) { Text(stringResource(R.string.form_action_cancel)) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProxySelector(
    proxies: List<ProxyEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = proxies.find { it.id == selectedId }?.displayName
        ?: stringResource(R.string.form_proxy_none)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.form_proxy_section_title), style = MaterialTheme.typography.labelMedium)
        Box {
            Button(onClick = { expanded = true }, enabled = enabled) { Text(selectedLabel) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.form_proxy_none)) },
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
