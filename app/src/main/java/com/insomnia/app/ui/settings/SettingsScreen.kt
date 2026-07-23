package com.insomnia.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.navigation.NavHostController
import com.insomnia.content.contract.EndpointClientRegistryHolder
import com.insomnia.proxy.contract.ProxyProviderRegistryHolder
import com.insomnia.proxy.ui.ProxyRoutes
import com.insomnia.storage.ProxyEntity
import com.insomnia.storage.StorageBindingsHolder
import com.insomnia.storage.TitleLang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    nav: NavHostController,
) {
    BackHandler(onBack = { nav.popBackStack() })

    val scope = rememberCoroutineScope()
    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)
    val proxies by StorageBindingsHolder.get().proxyDao.observeAll()
        .collectAsState(initial = emptyList())
    val ctrlUiAvailable = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(proxies) {
        val registry = EndpointClientRegistryHolder.get()
        proxies.forEach { proxy ->
            if (proxy.id !in ctrlUiAvailable) {
                val has = withContext(Dispatchers.IO) {
                    runCatching { registry.getProxyClient(proxy.id)?.ctrlUI != null }
                        .getOrDefault(false)
                }
                ctrlUiAvailable[proxy.id] = has
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("Settings")

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Title Language")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { scope.launch { StorageBindingsHolder.get().appConfigStore.saveTitleLang(TitleLang.Local) } },
                ) {
                    Text(if (titleLang == TitleLang.Local) "● Local Title" else "Local Title")
                }
                Button(
                    onClick = { scope.launch { StorageBindingsHolder.get().appConfigStore.saveTitleLang(TitleLang.Original) } },
                ) {
                    Text(if (titleLang == TitleLang.Original) "● Original Title" else "Original Title")
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Proxies")
            proxies.forEach { proxy ->
                Button(
                    onClick = {
                        if (ctrlUiAvailable[proxy.id] == true) {
                            nav.navigate(ProxyRoutes.proxyCtrl(proxy.proxyType, proxy.id))
                        } else {
                            nav.navigate(ProxyRoutes.proxyEdit(proxy.proxyType, proxy.id))
                        }
                    },
                ) {
                    Text(proxy.displayName)
                }
            }
            AddProxyButton(
                onAddProxy = { pt -> nav.navigate(ProxyRoutes.proxyEdit(pt)) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddProxyButton(
    onAddProxy: (proxyType: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val proxyTypes = remember {
        runCatching { ProxyProviderRegistryHolder.get().allProxies().map { it.proxyType } }
            .getOrDefault(emptyList())
    }
    Box {
        Button(
            onClick = { expanded = true },
        ) {
            Text("[+ Add proxy]")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            proxyTypes.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type) },
                    onClick = { onAddProxy(type); expanded = false },
                )
            }
        }
    }
}
