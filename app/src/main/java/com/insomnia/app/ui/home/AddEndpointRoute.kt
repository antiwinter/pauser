package com.insomnia.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.insomnia.app.InsomniaApplication
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AddEndpointRoute(
    onSelectProvider: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") onSelectProxy: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as InsomniaApplication
    val providers by app.providerRegistry.providersFlow.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Add endpoint")
        providers.forEach { provider ->
            Button(onClick = { onSelectProvider(provider.protocol) }) {
                Text("Add ${provider.protocol}")
            }
        }
    }
}
