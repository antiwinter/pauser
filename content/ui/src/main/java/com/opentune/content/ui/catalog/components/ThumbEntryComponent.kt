package com.opentune.content.ui.catalog.components

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.opentune.content.contract.EntryInfo
import java.io.File

private fun coverImageModel(cover: String?): Any? = when {
    cover.isNullOrBlank() -> null
    cover.startsWith("http://", ignoreCase = true) ||
        cover.startsWith("https://", ignoreCase = true) -> cover
    else -> File(cover)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ThumbEntryComponent(
    item: EntryInfo?,
    onClick: () -> Unit,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onFocus != null) Modifier.onFocusChanged { if (it.isFocused) onFocus() }
                else Modifier
            ),
    ) {
        if (item == null) {
            // Skeleton: episode data not yet available — gray placeholder, no thumb.png
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                        .fillMaxWidth(0.6f)
                        .height(12.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        } else {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    // Bottom layer: placeholder asset for entries without cover art
                    AsyncImage(
                        model = "file:///android_asset/art/thumb.png",
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Crop,
                    )

                    // Top layer: actual cover image (transparent if genart failed)
                    val model = coverImageModel(item.cover)
                    if (model != null) {
                        AsyncImage(
                            model = model,
                            contentDescription = item.title,
                            imageLoader = imageLoader,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Crop,
                        )
                    }

                    // Episode number badge top-left
                    item.indexNumber?.let { num ->
                        Text(
                            text = "E$num",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = item.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

