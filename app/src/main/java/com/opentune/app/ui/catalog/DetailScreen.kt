package com.opentune.app.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import coil3.compose.AsyncImage
import com.opentune.provider.EntryDetail
import com.opentune.provider.EntryInfo
import com.opentune.provider.EntryType
import com.opentune.storage.TitleLang
import java.io.File
import kotlin.math.ceil

private fun artImageModel(src: String?): Any? = when {
    src.isNullOrBlank() -> null
    src.startsWith("http://", ignoreCase = true) ||
        src.startsWith("https://", ignoreCase = true) -> src
    src.startsWith("file://") -> src
    else -> File(src)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailScreen(
    initialInfo: EntryInfo?,
    detail: EntryDetail?,
    loading: Boolean,
    isFavorite: Boolean,
    resumeMs: Long,
    titleLang: TitleLang,
    seasons: List<EntryInfo>?,
    selectedSeasonIndex: Int,
    episodes: List<EntryInfo>,
    totalEpisodes: Int,
    episodePage: Int,
    children: List<EntryInfo>,
    singleChild: EntryInfo?,
    onBack: () -> Unit,
    onPlayFromStart: () -> Unit,
    onResume: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onSelectEpisode: (EntryInfo) -> Unit,
    onSelectPage: (Int) -> Unit,
    onSelectChild: (EntryInfo) -> Unit,
    onPlaySingleChild: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop as full-screen background (two-layer: asset + image)
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
            if (detail != null && detail.backdrop.isNotEmpty()) {
                AsyncImage(
                    model = artImageModel(detail.backdrop.first()),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        // Gradient overlay
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

        // Back button
        Button(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp),
        ) { Text("Back") }

        // Content overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading && detail == null) {
                Text("Loading…")
            } else if (detail != null || initialInfo != null) {
                val displayTitle = when {
                    initialInfo != null && titleLang == TitleLang.Original ->
                        initialInfo.originalTitle ?: detail?.title
                    detail != null -> detail.title
                    else -> initialInfo?.title
                } ?: ""

                // Logo or title
                val logoModel = artImageModel(detail?.logo)
                if (logoModel != null) {
                    Box(
                        modifier = Modifier.height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = "file:///android_asset/art/logo.png",
                            contentDescription = null,
                            modifier = Modifier.height(80.dp),
                            contentScale = ContentScale.Fit,
                        )
                        AsyncImage(
                            model = logoModel,
                            contentDescription = displayTitle,
                            modifier = Modifier.height(80.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                } else {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }

                // Badges
                val videoCodecTitle = detail?.streams
                    ?.firstOrNull { it.type == "Video" }?.title
                val audioCodecTitle = detail?.streams
                    ?.firstOrNull { it.type == "Audio" }?.title
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    detail?.rating?.let { Badge("★ ${"%.1f".format(it)}") }
                    videoCodecTitle?.let { Badge(it) }
                    audioCodecTitle?.let { Badge(it) }
                }

                // Action buttons
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (initialInfo?.type) {
                        EntryType.Digipak -> {
                            val childCount = initialInfo.childCount ?: 0
                            if (childCount <= 1 && singleChild != null) {
                                if (resumeMs > 0) {
                                    Button(onClick = onPlaySingleChild) { Text("Resume") }
                                }
                                Button(onClick = onPlaySingleChild) {
                                    Text(if (resumeMs > 0) "From start" else "Play")
                                }
                            }
                        }
                        else -> {
                            if (detail?.isMedia == true) {
                                if (resumeMs > 0) {
                                    Button(onClick = onResume) { Text("Resume") }
                                }
                                Button(onClick = onPlayFromStart) {
                                    Text(if (resumeMs > 0) "From start" else "Play")
                                }
                            }
                        }
                    }
                    Button(onClick = onToggleFavorite) {
                        Text(if (isFavorite) "♥ Liked" else "♡ Like")
                    }
                }

                // Overview — prefer initialInfo.overview (available immediately), fall back to detail
                val overviewText = initialInfo?.overview ?: detail?.overview
                overviewText?.let { overview ->
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.87f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Series: Season selector + episodes
                if (!seasons.isNullOrEmpty() && seasons.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(seasons) { index, season ->
                            Button(onClick = { onSelectSeason(index) }) {
                                Text(
                                    text = season.title,
                                    fontWeight = if (index == selectedSeasonIndex)
                                        FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                if (seasons != null && episodes.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(episodes, key = { it.id }) { episode ->
                            ThumbEntryComponent(
                                item = episode,
                                onClick = { onSelectEpisode(episode) },
                                modifier = Modifier.width(200.dp),
                            )
                        }
                    }
                }

                if (seasons != null && totalEpisodes > 50) {
                    val pageCount = ceil(totalEpisodes / 50.0).toInt()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pageCount) { page ->
                            val start = page * 50 + 1
                            val end = minOf((page + 1) * 50, totalEpisodes)
                            Button(onClick = { onSelectPage(page) }) {
                                Text(
                                    text = "$start–$end",
                                    fontWeight = if (page == episodePage)
                                        FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                // Digipak: child thumbnails (multi-child)
                if (initialInfo?.type == EntryType.Digipak && initialInfo.childCount != null && initialInfo.childCount!! > 1 && children.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(children, key = { it.id }) { child ->
                            ThumbEntryComponent(
                                item = child,
                                onClick = { onSelectChild(child) },
                                modifier = Modifier.width(200.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.18f), shape = MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
