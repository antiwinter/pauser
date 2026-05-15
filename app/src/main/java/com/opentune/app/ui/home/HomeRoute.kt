package com.opentune.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opentune.app.OpenTuneApplication
import com.opentune.app.R
import com.opentune.storage.ServerEntity
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeRoute(
    onAddProvider: (String) -> Unit,
    onOpenBrowse: (String, String, String) -> Unit,
    onEditProvider: (String, String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as OpenTuneApplication
    val providers by app.providerRegistry.providersFlow.collectAsState()
    var serversByType by remember { mutableStateOf<Map<String, List<ServerEntity>>>(emptyMap()) }

    // Start watching server list for each provider as it arrives.
    // Uses an observed-protocols set so existing watchers are never cancelled when
    // the list grows — only new providers get a new watcher coroutine.
    LaunchedEffect(app) {
        val observedProtocols = mutableSetOf<String>()
        app.providerRegistry.providersFlow.collect { allProviders ->
            allProviders.forEach { provider ->
                if (observedProtocols.add(provider.protocol)) {
                    launch {
                        app.storageBindings.serverDao.observeByProvider(provider.protocol).collect { list ->
                            serversByType = serversByType + (provider.protocol to list)
                            launch { app.instanceRegistry.populateEager(list) }
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.home_title))
        providers.forEach { provider ->
            Button(onClick = { onAddProvider(provider.protocol) }) {
                Text(stringResource(R.string.home_add_provider, provider.protocol))
            }
        }
        providers.forEach { provider ->
            (serversByType[provider.protocol] ?: emptyList()).forEach { s ->
                Button(
                    onClick = { onOpenBrowse(provider.protocol, s.sourceId, "") },
                    modifier = Modifier.onTvMenuKeyDown { onEditProvider(provider.protocol, s.sourceId) },
                ) {
                    Text(s.displayName)
                }
            }
        }
    }
}
