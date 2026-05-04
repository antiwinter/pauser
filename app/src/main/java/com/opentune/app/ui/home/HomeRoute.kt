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
import com.opentune.provider.OpenTuneProviderIds
import com.opentune.provider.ServerRecord
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeRoute(
    onAddProvider: (String) -> Unit,
    onOpenBrowse: (String, Long, String) -> Unit,
    onEditProvider: (String, Long) -> Unit,
) {
    val app = LocalContext.current.applicationContext as OpenTuneApplication
    var httpServers by remember { mutableStateOf<List<ServerRecord>>(emptyList()) }
    var fileShares by remember { mutableStateOf<List<ServerRecord>>(emptyList()) }
    val librariesRoot = remember { CatalogNav.LIBRARIES_ROOT_SEGMENT }

    LaunchedEffect(app) {
        coroutineScope {
            launch {
                app.storageBindings.serverStore.observeByProvider(OpenTuneProviderIds.HTTP_LIBRARY).collect {
                    httpServers = it
                }
            }
            launch {
                app.storageBindings.serverStore.observeByProvider(OpenTuneProviderIds.FILE_SHARE).collect {
                    fileShares = it
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.home_title))
        Button(onClick = { onAddProvider(OpenTuneProviderIds.HTTP_LIBRARY) }) {
            Text(stringResource(R.string.home_add_http))
        }
        Button(onClick = { onAddProvider(OpenTuneProviderIds.FILE_SHARE) }) {
            Text(stringResource(R.string.home_add_file_share))
        }
        httpServers.forEach { s ->
            Button(
                onClick = { onOpenBrowse(OpenTuneProviderIds.HTTP_LIBRARY, s.id, librariesRoot) },
                modifier = Modifier.onTvMenuKeyDown { onEditProvider(OpenTuneProviderIds.HTTP_LIBRARY, s.id) },
            ) {
                Text(s.displayName)
            }
        }
        fileShares.forEach { s ->
            Button(
                onClick = { onOpenBrowse(OpenTuneProviderIds.FILE_SHARE, s.id, "") },
                modifier = Modifier.onTvMenuKeyDown { onEditProvider(OpenTuneProviderIds.FILE_SHARE, s.id) },
            ) {
                Text(s.displayName)
            }
        }
    }
}
