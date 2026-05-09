package com.opentune.app.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.storage.TitleLang
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: OpenTuneApplication,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val titleLang by app.storageBindings.appConfigStore.titleLangFlow
        .collectAsState(initial = TitleLang.Local)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Button(onClick = onBack) { Text("Back") }

        Text("Settings")

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Title Language")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { scope.launch { app.storageBindings.appConfigStore.saveTitleLang(TitleLang.Local) } },
                ) {
                    Text(if (titleLang == TitleLang.Local) "● Local Title" else "Local Title")
                }
                Button(
                    onClick = { scope.launch { app.storageBindings.appConfigStore.saveTitleLang(TitleLang.Original) } },
                ) {
                    Text(if (titleLang == TitleLang.Original) "● Original Title" else "Original Title")
                }
            }
        }
    }
}
