package com.insomnia.app.ui.home

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.insomnia.app.BuildConfig
import com.insomnia.app.InsomniaApplication
import com.insomnia.app.R
import com.insomnia.core.osd.gOSD
import com.insomnia.proxy.contract.ProxyClient
import com.insomnia.storage.EndpointEntity
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/** Default button palette: unselected = dark gray surface, selected/focused = accent blue. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun buttonColors() = ButtonDefaults.colors(
    containerColor        = MaterialTheme.colorScheme.surfaceVariant,
    contentColor          = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = MaterialTheme.colorScheme.primary,
    focusedContentColor   = MaterialTheme.colorScheme.onPrimary,
    pressedContainerColor = MaterialTheme.colorScheme.primary,
    pressedContentColor   = MaterialTheme.colorScheme.onPrimary,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeRoute(
    onAddEndpoint: () -> Unit,
    onOpenBrowse: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    onEditProvider: (String, String) -> Unit,
    onEditProxy: (String, String) -> Unit,
    onCtrlProxy: (String, String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as InsomniaApplication
    val scope = rememberCoroutineScope()
    val providers by app.providerRegistry.providersFlow.collectAsState()
    var endpointsByType by remember { mutableStateOf<Map<String, List<EndpointEntity>>>(emptyMap()) }
    val proxies by app.storageBindings.proxyDao.observeAll().collectAsState(initial = emptyList())
    var proxyClients by remember { mutableStateOf<Map<String, ProxyClient>>(emptyMap()) }

    // Build all proxy clients on launch
    LaunchedEffect(app) {
        proxyClients = app.endpointClientRegistry.getAllProxyClients()
    }

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
                (endpointsByType[provider.protocol] ?: emptyList()).forEach { e ->
                    Button(
                        onClick = { onOpenBrowse(provider.protocol, e.endpointId) },
                        modifier = Modifier.onTvMenuKeyDown { onEditProvider(provider.protocol, e.endpointId) },
                        colors = buttonColors(),
                    ) {
                        Text(e.displayName)
                    }
                }
            }
            proxies.forEach { proxy ->
                val client = proxyClients[proxy.id]
                Button(
                    onClick = {
                        if (client?.ctrlUI != null) {
                            onCtrlProxy(proxy.proxyType, proxy.id)
                        } else {
                            onEditProxy(proxy.proxyType, proxy.id)
                        }
                    },
                    modifier = Modifier.onTvMenuKeyDown {
                        onEditProxy(proxy.proxyType, proxy.id)
                    },
                    colors = buttonColors(),
                ) {
                    Text(proxy.displayName)
                }
            }
            Button(
                onClick = onOpenSettings,
                colors = buttonColors(),
            ) { Text("Settings") }
            Button(
                onClick = onAddEndpoint,
                colors = buttonColors(),
            ) {
                Text("[+]")
            }
            Button(
                onClick = { gOSD.msg("OSD test message") },
                colors = buttonColors(),
            ) {
                Text("[OSD test]")
            }
        }
        Text(
            text = BuildConfig.GIT_VERSION,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}
