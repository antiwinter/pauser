package com.opentune.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.storage.StorageBindingsHolder
import com.opentune.storage.TitleLang
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val titleLang by StorageBindingsHolder.get().appConfigStore.titleLangFlow
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
    }
}
