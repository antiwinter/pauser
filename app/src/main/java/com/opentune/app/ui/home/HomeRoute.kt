package com.opentune.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.opentune.app.ui.catalog.CatalogNav
import com.opentune.emby.api.EmbyProvider
import com.opentune.smb.SmbProvider
import com.opentune.storage.ServerEntity
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeRoute(
    onAddProvider: (String) -> Unit,
    onOpenBrowse: (String, String, String) -> Unit,
    onEditProvider: (String, String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as OpenTuneApplication
    var httpServers by remember { mutableStateOf<List<ServerEntity>>(emptyList()) }
    var fileShares by remember { mutableStateOf<List<ServerEntity>>(emptyList()) }
    val librariesRoot = remember { CatalogNav.LIBRARIES_ROOT_SEGMENT }

    LaunchedEffect(app) {
        coroutineScope {
            launch {
                app.storageBindings.serverDao.observeByProvider(EmbyProvider.PROVIDER_TYPE).collect { list ->
                    httpServers = list
                    launch { app.instanceRegistry.populateEager(list) }
                }
            }
            launch {
                app.storageBindings.serverDao.observeByProvider(SmbProvider.PROVIDER_TYPE).collect { list ->
                    fileShares = list
                    launch { app.instanceRegistry.populateEager(list) }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.home_title))
        Button(onClick = { onAddProvider(EmbyProvider.PROVIDER_TYPE) }) {
            Text(stringResource(R.string.home_add_http))
        }
        Button(onClick = { onAddProvider(SmbProvider.PROVIDER_TYPE) }) {
            Text(stringResource(R.string.home_add_file_share))
        }
        httpServers.forEach { s ->
            Button(
                onClick = { onOpenBrowse(EmbyProvider.PROVIDER_TYPE, s.sourceId, librariesRoot) },
                modifier = Modifier.onTvMenuKeyDown { onEditProvider(EmbyProvider.PROVIDER_TYPE, s.sourceId) },
            ) {
                Text(s.displayName)
            }
        }
        fileShares.forEach { s ->
            Button(
                onClick = { onOpenBrowse(SmbProvider.PROVIDER_TYPE, s.sourceId, "") },
                modifier = Modifier.onTvMenuKeyDown { onEditProvider(SmbProvider.PROVIDER_TYPE, s.sourceId) },
            ) {
                Text(s.displayName)
            }
        }
    }
}
