package com.opentune.content.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.opentune.content.contract.EntryInfo
import java.io.File

fun artImageModel(src: String?): Any? = when {
    src.isNullOrBlank() -> null
    src.startsWith("http://", ignoreCase = true) ||
        src.startsWith("https://", ignoreCase = true) -> src
    src.startsWith("file://") -> src
    else -> File(src)
}

/** Full-screen backdrop with asset fallback + item backdrop + gradient overlay */
@Composable
fun DetailBackdrop(
    backdropUrl: String?,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A)),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = "file:///android_asset/art/backdrop.png",
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = artImageModel(backdropUrl),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.5f),
                        1.0f to Color.Black.copy(alpha = 0.95f),
                    ),
                ),
            ),
    )
}

/** Row of info badges */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailBadges(
    entryInfo: EntryInfo,
) {
    val resolution = heightToResolutionLabel(entryInfo.height)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entryInfo.communityRating?.let { Badge("★ ${"%.1f".format(it)}") }
        entryInfo.year?.let { Badge(it.toString()) }
        if (resolution.isNotEmpty()) Badge(resolution)
        entryInfo.officialRating?.let { Badge(it) }
    }
}

/** Play buttons (Resume, From start / Play, Like) */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailPlayButtons(
    resumeMs: Long,
    isFavorite: Boolean,
    hasContent: Boolean,
    onResume: () -> Unit,
    onPlayFromStart: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    if (!hasContent) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (resumeMs > 0) {
            Button(onClick = onResume) { Text("Resume") }
        }
        Button(onClick = onPlayFromStart) {
            Text(if (resumeMs > 0) "From start" else "Play")
        }
        Button(onClick = onToggleFavorite) {
            Text(if (isFavorite) "♥ Liked" else "♡ Like")
        }
    }
}

/** Resolution label from height: SD/HD/FHD/QHD/4K/5K/8K */
fun heightToResolutionLabel(height: Int?): String = when {
    height == null -> ""
    height <= 480 -> "SD"
    height <= 720 -> "HD"
    height <= 1080 -> "FHD"
    height <= 1440 -> "QHD"
    height <= 2160 -> "4K"
    height <= 2880 -> "5K"
    else -> "8K"
}

/** Overview text (snippet mode) */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailOverviewSnippet(
    overview: String,
    maxLines: Int = 4,
) {
    Text(
        text = overview,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.87f),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Full overview text */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailOverviewFull(
    overview: String,
) {
    Text(
        text = overview,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.87f),
    )
}

/** Season selector row */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SeasonSelector(
    seasons: List<EntryInfo>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    if (seasons.size <= 1) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(seasons) { season ->
            val index = seasons.indexOf(season)
            Button(onClick = { onSelect(index) }) {
                Text(
                    text = season.title,
                    fontWeight = if (index == selectedIndex)
                        FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Episode row */
@Composable
fun EpisodeRow(
    episodes: List<EntryInfo>,
    imageLoader: ImageLoader,
    onPlayEpisode: (EntryInfo) -> Unit,
) {
    if (episodes.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(episodes, key = { it.id }) { episode ->
            ThumbEntryComponent(
                item = episode,
                onClick = { onPlayEpisode(episode) },
                imageLoader = imageLoader,
                modifier = Modifier.width(200.dp),
            )
        }
    }
}

/** Episode pagination */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EpisodePager(
    totalEpisodes: Int,
    currentPage: Int,
    onSelectPage: (Int) -> Unit,
) {
    if (totalEpisodes <= 50) return
    val pageCount = kotlin.math.ceil(totalEpisodes / 50.0).toInt()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pageCount) { page ->
            val start = page * 50 + 1
            val end = minOf((page + 1) * 50, totalEpisodes)
            Button(onClick = { onSelectPage(page) }) {
                Text(
                    text = "$start–$end",
                    fontWeight = if (page == currentPage)
                        FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Digipak children row */
@Composable
fun DigipakChildren(
    children: List<EntryInfo>,
    imageLoader: ImageLoader,
    onPlayChild: (EntryInfo) -> Unit,
) {
    if (children.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(children, key = { it.id }) { child ->
            ThumbEntryComponent(
                item = child,
                onClick = { onPlayChild(child) },
                imageLoader = imageLoader,
                modifier = Modifier.width(200.dp),
            )
        }
    }
}

/** Page indicator dots */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(pageCount) { i ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(8.dp)
                    .width(if (i == currentPage) 16.dp else 8.dp)
                    .background(
                        if (i == currentPage)
                            Color.White
                        else
                            Color.White.copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.extraSmall,
                    ),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun Badge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.18f), shape = MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
