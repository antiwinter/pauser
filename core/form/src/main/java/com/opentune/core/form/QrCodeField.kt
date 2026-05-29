package com.opentune.core.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.opentune.core.form.contract.QrStatus

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun QrCodeField(
    qrData: String,
    status: QrStatus,
    onRefresh: () -> Unit,
    enabled: Boolean = true,
) {
    val expired = status == QrStatus.EXPIRED || status == QrStatus.CANCELED
    val dimmed  = expired || status == QrStatus.CONFIRMED
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = qrData,
                contentDescription = null,
                modifier = Modifier
                    .size(200.dp)
                    .alpha(if (dimmed) 0.3f else 1f),
            )
            if (status == QrStatus.NEW) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        }
        val statusText = when (status) {
            QrStatus.NEW -> stringResource(R.string.qr_status_new)
            QrStatus.SCANNED -> stringResource(R.string.qr_status_scanned)
            QrStatus.EXPIRED, QrStatus.CANCELED -> stringResource(R.string.qr_status_expired)
            QrStatus.CONFIRMED -> stringResource(R.string.qr_status_confirmed)
        }
        if (statusText.isNotEmpty()) {
            Text(statusText)
        }
        if (expired) {
            Button(onClick = onRefresh, enabled = enabled) {
                Text(stringResource(R.string.qr_refresh))
            }
        }
    }
}
