package com.opentune.app.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.R
import com.opentune.app.providers.ProxyConfigRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProxyManageRoute(
    onAddProxy: (proxyType: String) -> Unit,
    onEditProxy: (proxyType: String, proxyConfigId: String) -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OpenTuneApplication
    val scope = rememberCoroutineScope()
    val proxies by app.storageBindings.proxyConfigDao.observeAll()
        .collectAsState(initial = emptyList())
    var snackMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.proxy_manage_title), style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack) { Text(stringResource(R.string.action_cancel)) }
        }

        app.proxyProviderRegistry.allProxies().forEach { provider ->
            Button(onClick = { onAddProxy(provider.proxyType) }) {
                Text("+ Add ${provider.proxyType} proxy")
            }
        }

        proxies.forEach { proxy ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(proxy.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(proxy.proxyType, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { onEditProxy(proxy.proxyType, proxy.id) }) {
                    Text("Edit")
                }
                Button(
                    onClick = {
                        scope.launch {
                            val affected = ProxyConfigRepository.delete(proxy.id, app)
                            snackMessage = if (affected > 0)
                                "Proxy deleted. $affected server(s) now use direct connection."
                            else
                                "Proxy deleted."
                        }
                    }
                ) {
                    Text("Delete")
                }
            }
        }

        snackMessage?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
