package com.opentune.app.ui.catalog

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.opentune.provider.MediaArt
import com.opentune.provider.MediaDetailModel
import java.io.File

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailScreen(
    detail: MediaDetailModel?,
    loading: Boolean,
    error: String?,
    isFavorite: Boolean,
    resumePositionMs: Long,
    onBack: () -> Unit,
    onPlayFromStart: () -> Unit,
    onResume: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onBack) { Text("Back") }
        error?.let { Text("Error: $it") }
        when {
            loading && detail == null -> Text("Loading…")
            detail == null -> Unit
            else -> {
                val d = detail
                Text(d.title)
                if (d.canPlay) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (resumePositionMs > 0) {
                            Button(onClick = onResume) { Text("Resume") }
                        }
                        Button(onClick = onPlayFromStart) {
                            Text(if (resumePositionMs > 0) "From start" else "Play")
                        }
                    }
                }
                if (d.favoriteSupported) {
                    Button(onClick = onToggleFavorite) {
                        Text(if (isFavorite) "Remove favorite" else "Add favorite")
                    }
                }
                when (val c = d.cover) {
                    is MediaArt.Http -> AsyncImage(
                        model = c.url,
                        contentDescription = d.title,
                        modifier = Modifier
                            .height(280.dp)
                            .padding(bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                    )
                    is MediaArt.DrawableRes -> Image(
                        painter = painterResource(c.resId),
                        contentDescription = d.title,
                        modifier = Modifier
                            .height(200.dp)
                            .padding(bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                    )
                    is MediaArt.LocalFile -> AsyncImage(
                        model = File(c.absolutePath),
                        contentDescription = d.title,
                        modifier = Modifier
                            .height(280.dp)
                            .padding(bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                    )
                    MediaArt.None -> Unit
                }
                d.synopsis?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}
