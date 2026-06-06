package com.opentune.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import com.opentune.app.BuildConfig
import com.opentune.app.OpenTuneApplication
import com.opentune.app.R
import com.opentune.content.ui.providers.ProxyRepository
import com.opentune.storage.EndpointEntity
import com.opentune.storage.ProxyEntity
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeRoute(
    onAddProvider: (String) -> Unit,
    onOpenBrowse: (String, String) -> Unit,
    onEditProvider: (String, String) -> Unit,
    onAddProxy: (String) -> Unit,
    onEditProxy: (String, String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as OpenTuneApplication
    val providers by app.providerRegistry.providersFlow.collectAsState()
    var endpointsByType by remember { mutableStateOf<Map<String, List<EndpointEntity>>>(emptyMap()) }
    val proxies by app.storageBindings.proxyDao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    LaunchedEffect(app) {
        val observedProtocols = mutableSetOf<String>()
        app.providerRegistry.providersFlow.collect { allProviders ->
            allProviders.forEach { provider ->
                if (observedProtocols.add(provider.protocol)) {
                    launch {
                        app.storageBindings.endpointDao.observeByProvider(provider.protocol).collect { list ->
                            endpointsByType = endpointsByType + (provider.protocol to list)
                            launch { app.endpointClientRegistry.populateEager(list) }
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                (endpointsByType[provider.protocol] ?: emptyList()).forEach { e ->
                    Button(
                        onClick = { onOpenBrowse(provider.protocol, e.endpointId) },
                        modifier = Modifier.onTvMenuKeyDown { onEditProvider(provider.protocol, e.endpointId) },
                    ) {
                        Text(e.displayName)
                    }
                }
            }
            app.proxyProviderRegistry.allProxies().forEach { proxyProvider ->
                Button(onClick = { onAddProxy(proxyProvider.proxyType) }) {
                    Text("+ Add ${proxyProvider.proxyType} proxy")
                }
            }
            proxies.forEach { proxy ->
                Button(
                    onClick = { onEditProxy(proxy.proxyType, proxy.id) },
                    modifier = Modifier.onTvMenuKeyDown {
                        scope.launch { ProxyRepository.delete(proxy.id) }
                    },
                ) {
                    Text(proxy.displayName)
                }
            }
        }
        Text(
            text = BuildConfig.GIT_VERSION,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}
