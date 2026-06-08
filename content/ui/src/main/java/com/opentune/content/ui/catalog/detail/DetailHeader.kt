package com.opentune.content.ui.catalog.detail

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.opentune.content.contract.EntryInfo
import com.opentune.storage.TitleLang

/**
 * Logo (if available) or title text — shared header for all detail overview pages.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailHeader(entryInfo: EntryInfo, titleLang: TitleLang) {
    val logoModel = artImageModel(entryInfo.logo)
    if (logoModel != null) {
        AsyncImage(
            model = logoModel,
            contentDescription = null,
            modifier = Modifier.padding(bottom = 8.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        val displayTitle = when (titleLang) {
            TitleLang.Original -> entryInfo.originalTitle
            else -> null
        } ?: entryInfo.title
        Text(
            text = displayTitle,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}
