package com.insomnia.core.form

import timber.log.Timber
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.insomnia.core.form.contract.FormFieldKind
import com.insomnia.core.form.contract.FormFieldSpec
import com.insomnia.core.form.contract.QrResult
import com.insomnia.core.form.contract.QrStatus
import com.insomnia.storage.ProxyEntity
import com.insomnia.storage.StorageBindingsHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class QrUiState {
    data object Idle : QrUiState()
    data object Loading : QrUiState()
    data class Active(val token: String, val qrData: String, val status: QrStatus) : QrUiState()
    data class Failed(val message: String) : QrUiState()
}

@OptIn(ExperimentalTvMaterial3Api::class, FlowPreview::class)
@Composable
fun ProviderFormRoute(
    fields: List<FormFieldSpec>,
    draftKey: String? = null,
    onLoad: (suspend () -> Pair<Map<String, String>, String?>)? = null,
    onSubmit: suspend (values: Map<String, String>, proxyId: String?) -> SubmitResult,
    // Called each time the selected proxy changes. Must not catch CancellationException —
    // the form cancels a pending call when the proxy changes and immediately issues a new one.
    // Any broad catch(Exception) in the implementation will eat the cancellation and let a
    // stale result race against the new call.
    onGetQr: (suspend (proxyId: String?) -> QrResult.QrReady?)? = null,
    onPollQr: (suspend (token: String) -> QrResult)? = null,
    onDone: () -> Unit,
    onDelete: (suspend () -> Unit)? = null,
) {
    BackHandler(onBack = onDone)

    val sortedFields    = remember(fields) { fields.sortedBy { it.order } }
    val nonQrFields     = remember(sortedFields) { sortedFields.filter { it.kind != FormFieldKind.QrCode && it.kind != FormFieldKind.ProxySelector } }
    val qrFields        = remember(sortedFields) { sortedFields.filter { it.kind == FormFieldKind.QrCode } }
    val hasProxySelector = remember(sortedFields) { sortedFields.any { it.kind == FormFieldKind.ProxySelector } }

    var values by remember { mutableStateOf(nonQrFields.associate { it.id to (it.defaultValue ?: "") }) }
    var selectedProxyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(onLoad == null && draftKey == null) }
    var qrState by remember { mutableStateOf<QrUiState>(if (onGetQr != null) QrUiState.Loading else QrUiState.Idle) }
    var qrConfirmedFields by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    val proxies by StorageBindingsHolder.get().proxyDao.observeAll()
        .collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        when {
            onLoad != null -> {
                val (initial, proxyId) = withContext(Dispatchers.IO) { onLoad() }
                values = nonQrFields.associate { it.id to (initial[it.id] ?: it.defaultValue ?: "") }
                selectedProxyId = proxyId
            }
            draftKey != null -> {
                val draft = withContext(Dispatchers.IO) {
                    StorageBindingsHolder.get().appConfigStore.loadDraft(draftKey)
                }
                values = nonQrFields.associate { it.id to (draft[it.id] ?: it.defaultValue ?: "") }
            }
            else -> {}
        }
        loaded = true
    }

    if (draftKey != null) {
        LaunchedEffect(nonQrFields) {
            snapshotFlow { values }
                .distinctUntilChanged()
                .debounce(600)
                .collect { v ->
                    withContext(Dispatchers.IO) {
                        StorageBindingsHolder.get().appConfigStore.saveDraft(draftKey, v)
                    }
                }
        }
    }

    LaunchedEffect(loaded, selectedProxyId) {
        if (!loaded || onGetQr == null) return@LaunchedEffect
        qrState = QrUiState.Loading
        val ready = withContext(Dispatchers.IO) { onGetQr(selectedProxyId) }
        qrState = if (ready == null) QrUiState.Failed("QR unavailable")
                  else QrUiState.Active(ready.token, ready.qrData, QrStatus.NEW)
    }

    LaunchedEffect(qrState) {
        val active = qrState as? QrUiState.Active ?: return@LaunchedEffect
        if (active.status != QrStatus.NEW && active.status != QrStatus.SCANNED) return@LaunchedEffect
        while (true) {
            delay(1000)
            when (val poll = withContext(Dispatchers.IO) {
                onPollQr?.invoke(active.token) ?: QrResult.Error("no poll fn")
            }) {
                is QrResult.Scanned   -> qrState = active.copy(status = QrStatus.SCANNED)
                is QrResult.Expired   -> qrState = active.copy(status = QrStatus.EXPIRED)
                is QrResult.Confirmed -> {
                    qrConfirmedFields = poll.fields
                    qrState = active.copy(status = QrStatus.CONFIRMED)
                    return@LaunchedEffect
                }
                is QrResult.Error -> {
                    qrState = QrUiState.Failed(poll.message)
                    return@LaunchedEffect
                }
                else -> {}
            }
        }
    }

    val canSubmit = !isLoading && loaded &&
        (onGetQr == null || (qrState as? QrUiState.Active)?.status == QrStatus.CONFIRMED)

    val scroll = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.form_title))
            if (!loaded) {
                Text(stringResource(R.string.form_loading))
            } else {
                FormFieldsRenderer(
                    fields = nonQrFields,
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
            Button(
                onClick = {
                    if (!canSubmit) return@Button
                    scope.launch {
                        error = null
                        isLoading = true
                        try {
                            val merged = values + qrConfirmedFields
                            when (val result = withContext(Dispatchers.IO) { onSubmit(merged, selectedProxyId) }) {
                                is SubmitResult.Success -> {
                                    if (draftKey != null) withContext(Dispatchers.IO) {
                                        StorageBindingsHolder.get().appConfigStore.clearDraft(draftKey)
                                    }
                                    onDone()
                                }
                                is SubmitResult.Error -> {
                                    Timber.e("submit failed: ${result.message}")
                                    error = result.message
                                }
                            }
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = canSubmit,
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
            if (onDelete != null) {
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            try {
                                withContext(Dispatchers.IO) { onDelete() }
                                onDone()
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = !isLoading,
                ) { Text(stringResource(R.string.form_action_delete)) }
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (qrFields.isNotEmpty()) {
                when (val state = qrState) {
                    is QrUiState.Loading -> CircularProgressIndicator()
                    is QrUiState.Active  -> QrCodeField(
                        qrData = state.qrData,
                        status = state.status,
                        onRefresh = {
                            scope.launch {
                                qrState = QrUiState.Loading
                                val ready = withContext(Dispatchers.IO) { onGetQr?.invoke(selectedProxyId) }
                                qrState = if (ready == null) QrUiState.Failed("QR unavailable")
                                          else QrUiState.Active(ready.token, ready.qrData, QrStatus.NEW)
                            }
                        },
                        enabled = !isLoading,
                    )
                    is QrUiState.Failed  -> Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                    is QrUiState.Idle    -> {}
                }
            }
        }
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
