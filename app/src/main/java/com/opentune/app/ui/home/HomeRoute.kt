package com.opentune.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.R
import com.opentune.app.providers.OpenTuneProviderIds
import com.opentune.app.ui.catalog.CatalogNav
import com.opentune.storage.OpenTuneDatabase

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeRoute(
    database: OpenTuneDatabase,
    onAddProvider: (String) -> Unit,
    onOpenBrowse: (String, Long, String) -> Unit,
    onEditProvider: (String, Long) -> Unit,
) {
    val servers by database.embyServerDao().observeAll()
        .collectAsState(initial = emptyList())
    val fileShares by database.smbSourceDao().observeAll()
        .collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.home_title))
        Button(onClick = { onAddProvider(OpenTuneProviderIds.HTTP_LIBRARY) }) {
            Text(stringResource(R.string.home_add_http))
        }
        Button(onClick = { onAddProvider(OpenTuneProviderIds.FILE_SHARE) }) {
            Text(stringResource(R.string.home_add_file_share))
        }
        Text(text = stringResource(R.string.home_section_http_sources))
        servers.forEach { s ->
            Button(
                onClick = {
                    onOpenBrowse(OpenTuneProviderIds.HTTP_LIBRARY, s.id, CatalogNav.LIBRARIES_ROOT_SEGMENT)
                },
                modifier = Modifier.onTvMenuKeyDown { onEditProvider(OpenTuneProviderIds.HTTP_LIBRARY, s.id) },
            ) {
                Text(s.displayName)
            }
        }
        Text(text = stringResource(R.string.home_section_file_sources))
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
