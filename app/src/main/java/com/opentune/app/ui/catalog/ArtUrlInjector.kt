package com.opentune.app.ui.catalog

import com.opentune.app.OpenTuneApplication
import com.opentune.provider.EntryDetail
import com.opentune.provider.EntryInfo
import com.opentune.server.SERVER_PORT

enum class ArtType { Cover, Thumb }

object ArtUrlInjector {

    /** Mutates [items] in place, injecting genart URLs for `!providesArt` providers. */
    fun apply(
        items: List<EntryInfo>,
        app: OpenTuneApplication,
        protocol: String,
        sourceId: String,
        artType: ArtType = ArtType.Cover,
    ): List<EntryInfo> {
        val providesArt = app.providerRegistry.provider(protocol).providesArt
        if (providesArt) return items
        val prefix = when (artType) {
            ArtType.Cover -> "genart/cover"
            ArtType.Thumb -> "genart/thumb"
        }
        return items.map { item ->
            if (item.cover == null) {
                item.copy(cover = "http://localhost:$SERVER_PORT/$prefix/$sourceId/${item.id}")
            } else item
        }
    }

    /** Mutates [detail] in place, injecting asset URLs for `!providesArt` providers. */
    fun applyDetail(
        detail: EntryDetail,
        app: OpenTuneApplication,
        protocol: String,
    ): EntryDetail {
        val providesArt = app.providerRegistry.provider(protocol).providesArt
        if (providesArt) return detail
        return detail.copy(
            logo = if (detail.logo == null) "file:///android_asset/art/logo.png" else detail.logo,
            backdrop = if (detail.backdrop.isEmpty()) listOf("file:///android_asset/art/backdrop.png") else detail.backdrop,
        )
    }
}
