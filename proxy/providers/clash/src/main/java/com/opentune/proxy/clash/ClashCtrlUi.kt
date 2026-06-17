package com.opentune.proxy.clash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.core.form.TvOutlinedTextField
import kotlinx.coroutines.launch

private fun latencyColor(ms: Long?): Color = when {
    ms == null || ms < 0 -> Color(0xFF808080)
    ms < 100            -> Color(0xFF2E7D32)
    ms < 300            -> Color(0xFFF9A825)
    else                -> Color(0xFFC62828)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ClashCtrlUi(
    proxyId: String,
    client: ClashProxyClient,
    onNavigateToEdit: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<ClashProxyLine>>(emptyList()) }
    var latencies by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var activeProxy by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var subscriptionUrl by remember { mutableStateOf("") }

    fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        scope.launch {
            try {
                val fetched = client.fetchProxyLines()
                lines = fetched
                activeProxy = client.getActiveProxy()
                val lats = client.testLatencyParallel(fetched)
                latencies = lats
            } finally {
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = { refresh() },
                modifier = Modifier.width(100.dp),
            ) {
                Text(if (isRefreshing) "..." else "Refresh")
            }

            TvOutlinedTextField(
                value = subscriptionUrl,
                onValueChange = { subscriptionUrl = it },
                label = { Text("Subscription URL", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )

            Button(
                onClick = onNavigateToEdit,
                modifier = Modifier.width(80.dp),
            ) {
                Text("⚙")
            }
        }

        // Proxy line grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            items(lines, key = { it.name }) { line ->
                val latMs = latencies[line.name]
                val isActive = line.name == activeProxy
                ProxyLineChip(
                    name = line.name,
                    latencyMs = latMs,
                    isActive = isActive,
                    onClick = {
                        scope.launch {
                            client.setActiveProxy(line.name)
                            activeProxy = line.name
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProxyLineChip(
    name: String,
    latencyMs: Long?,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = latencyColor(latencyMs).copy(alpha = 0.18f)
    val borderColor = if (isActive) Color(0xFF1565C0) else Color.Transparent

    Button(
        onClick = onClick,
        modifier = Modifier
            .height(64.dp)
            .fillMaxWidth()
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
            .background(bgColor, RoundedCornerShape(8.dp)),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            Text(
                text = name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            )
            Text(
                text = latencyMs?.takeIf { it >= 0 }?.let { "${it}ms" } ?: "—",
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}
