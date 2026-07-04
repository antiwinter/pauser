package com.insomnia.content.ui.catalog.detail

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.insomnia.content.contract.EntryInfo
import com.insomnia.player.EntryStateKeys
import com.insomnia.content.ui.catalog.player.PlayerController
import com.insomnia.player.MediaCodecInfo
import kotlinx.coroutines.flow.MutableStateFlow
import com.insomnia.storage.TitleLang
import java.io.File

fun artImageModel(src: String?): Any? = when {
    src.isNullOrBlank() -> null
    src.startsWith("http://", ignoreCase = true) ||
        src.startsWith("https://", ignoreCase = true) -> src
    src.startsWith("file://") -> src
    else -> File(src)
}

/** Logo (if available) or title text — shared header for all detail overview pages. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailHeader(viewModel: DetailViewModel) {
    val entryInfo by viewModel.entryInfo.collectAsState()
    val info = entryInfo ?: return
    val titleLang by viewModel.appConfig.titleLangFlow
        .collectAsState(initial = TitleLang.Local)
    val logoModel = artImageModel(info.logo)
    if (logoModel != null) {
        AsyncImage(
            model = logoModel,
            contentDescription = null,
            modifier = Modifier.padding(bottom = 8.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        val displayTitle = when (titleLang) {
            TitleLang.Original -> info.originalTitle
            else -> null
        } ?: info.title
        Text(
            text = displayTitle,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/**
 * Two-page pager shell shared by Movie, Series, and Digipak detail screens.
 *
 * Page 0: [page1Content] — type-specific content provided by the caller.
 * Page 1: Full-screen backdrop + full overview text.
 */
@Composable
fun DetailOverviewShell(
    viewModel: DetailViewModel,
    page1Content: @Composable () -> Unit,
) {
    val entryInfo by viewModel.entryInfo.collectAsState()
    val info = entryInfo ?: return
    val pagerState = rememberPagerState { 2 }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false,
        ) { page ->
            when (page) {
                0 -> page1Content()
                1 -> DetailPage2(entryInfo = info)
            }
        }

        PageIndicator(
            pageCount = 2,
            currentPage = pagerState.currentPage,
        )
    }
}

@Composable
private fun DetailPage2(entryInfo: EntryInfo) {
    Box(modifier = Modifier.fillMaxSize()) {
        DetailBackdrop(backdropUrl = entryInfo.backdrop.getOrNull(1) ?: entryInfo.backdrop.firstOrNull())

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            entryInfo.overview?.let { DetailOverviewFull(it) }
        }
    }
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
    viewModel: DetailViewModel,
    playerController: PlayerController?,
) {
    val entryInfo by viewModel.entryInfo.collectAsState()
    val info = entryInfo ?: return
    val mediaCodecs by (playerController?.mediaCodecs
        ?: MutableStateFlow(emptyList<MediaCodecInfo>())).collectAsState()
    val resolution = widthToResolutionLabel(info.width)
    val videoCodec = mediaCodecs.firstOrNull()
    val audioCodecs = mediaCodecs.drop(1)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        info.communityRating?.let { Badge("★ ${"%.1f".format(it)}") }
        info.year?.let { Badge(it.toString()) }
        if (resolution.isNotEmpty()) Badge(resolution)
        videoCodec?.bitDepth?.let { Badge("${it}bit") }
        videoCodec?.let { Badge(it.codec.uppercase()) }
        audioCodecs.forEach { Badge(it.codec.uppercase()) }
        info.officialRating?.let { Badge(it) }
        info.genres?.take(3)?.forEach { Badge(it) }
    }
}

/** Shared detail action buttons (favorite, etc.). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailButtons(viewModel: DetailViewModel) {
    val entryInfo by viewModel.entryInfo.collectAsState()
    val info = entryInfo ?: return
    val isFavorite = info.userData?.isFavorite ?: false
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            viewModel.updateEntryState(EntryStateKeys.FAVORITE, (!isFavorite).toString())
        }) {
            Text(if (isFavorite) "♥ Liked" else "♡ Like")
        }
    }
}

/** Resolution label from width: SD/HD/FHD/WQHD/4K/5K/8K */
fun widthToResolutionLabel(width: Int?): String = when {
    width == null -> ""
    width >= 7680 -> "8K"
    width >= 5120 -> "5K"
    width >= 3840 -> "4K"
    width >= 2560 -> "WQHD"
    width >= 1920 -> "FHD"
    width >= 1280 -> "HD"
    else -> "SD"
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
