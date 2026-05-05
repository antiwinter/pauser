package com.opentune.app.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.tv.material3.Surface
import com.opentune.provider.MediaArt
import java.io.File
import com.opentune.provider.MediaEntryKind
import com.opentune.provider.MediaListItem

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MediaEntryComponent(
    item: MediaListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.72f),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when (val c = item.cover) {
                    is MediaArt.Http -> AsyncImage(
                        model = c.url,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentScale = ContentScale.Crop,
                    )
                    is MediaArt.DrawableRes -> Image(
                        painter = painterResource(c.resId),
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentScale = ContentScale.Fit,
                    )
                    is MediaArt.LocalFile -> AsyncImage(
                        model = File(c.absolutePath),
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentScale = ContentScale.Crop,
                    )
                    MediaArt.None -> Text(
                        text = when (item.kind) {
                            MediaEntryKind.Folder -> "📁"
                            MediaEntryKind.Playable -> "▶"
                            MediaEntryKind.Other -> "•"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
            Text(
                text = item.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
