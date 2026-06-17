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
    ms == null           -> Color(0xFF808080)  // not tested yet
    ms < 0 || ms == 0L   -> Color(0xFFC62828)  // timeout / failed
    ms <= 200            -> Color(0xFF2E7D32)  // green: fast
    ms <= 300            -> Color(0xFFF9A825)  // yellow: medium
    else                 -> Color(0xFFE65100)  // orange: slow
}

private fun latencyText(ms: Long?): String = when {
    ms == null           -> "—"
    ms < 0 || ms == 0L   -> "timeout"
    else                 -> "${ms}ms"
}

/** Sort key: timeout/null go last, valid latencies sort ascending. */
private fun latencySortKey(ms: Long?): Long = when {
    ms == null           -> Long.MAX_VALUE
    ms < 0 || ms == 0L   -> Long.MAX_VALUE - 1  // timeout before unknown
    else                 -> ms
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
    var subscriptionLabel by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    /** Load current proxy lines from controller without testing latency. */
    fun load() {
        scope.launch {
            try {
                subscriptionLabel = client.fetchSubscriptionLabel()
                lines = client.fetchProxyLines()
                // Use cached latency from Clash proxy history if available
                latencies = lines.associate { it.name to (it.latencyMs ?: Long.MIN_VALUE) }
                    .filterValues { it != Long.MIN_VALUE }
                activeProxy = client.getActiveProxy()
            } catch (_: Exception) { }
        }
    }

    /** Refresh subscription + re-test all latency. */
    fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        errorMsg = null
        latencies = emptyMap()
        scope.launch {
            try {
                val subscriptionOk = if (subscriptionUrl.isNotBlank()) {
                    client.refreshSubscription(subscriptionUrl)
                } else true

                if (subscriptionOk) {
                    if (subscriptionUrl.isNotBlank()) subscriptionUrl = ""
                    subscriptionLabel = client.fetchSubscriptionLabel()
                    val fetched = client.fetchProxyLines()
                    val lats = client.testLatencyParallel(fetched)
                    latencies = lats
                    lines = fetched.sortedBy { latencySortKey(lats[it.name]) }
                    activeProxy = client.getActiveProxy()
                } else {
                    errorMsg = "Subscription failed"
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

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
                placeholder = if (subscriptionLabel != null) {
                    { Text(subscriptionLabel!!, fontSize = 12.sp, color = Color(0xFF808080)) }
                } else null,
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

        // Error message
        errorMsg?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(3000)
                errorMsg = null
            }
            Text(
                text = msg,
                color = Color(0xFFC62828),
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Proxy line grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
    val latColor = latencyColor(latencyMs)
    val bgColor = latColor.copy(alpha = 0.18f)
    val borderColor = if (isActive) Color(0xFF1565C0) else Color.Transparent

    Button(
        onClick = onClick,
        modifier = Modifier
            .height(40.dp)
            .fillMaxWidth()
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(6.dp))
            .background(bgColor, RoundedCornerShape(6.dp)),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
            Text(
                text = name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = latencyText(latencyMs),
                color = latColor,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}
