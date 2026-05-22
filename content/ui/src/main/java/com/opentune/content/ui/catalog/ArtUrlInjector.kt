package com.opentune.content.ui.catalog

import com.opentune.content.contract.EntryDetail
import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.OpenTuneProviderRegistryHolder
import com.opentune.server.SERVER_PORT

enum class ArtType { Cover, Thumb }

object ArtUrlInjector {

    fun apply(
        items: List<EntryInfo>,
        protocol: String,
        endpointId: String,
        artType: ArtType = ArtType.Cover,
    ): List<EntryInfo> {
        val providesArt = OpenTuneProviderRegistryHolder.get().provider(protocol).providesArt
        if (providesArt) return items
        val prefix = when (artType) {
            ArtType.Cover -> "genart/cover"
            ArtType.Thumb -> "genart/thumb"
        }
        return items.map { item ->
            if (item.cover == null) {
                item.copy(cover = "http://localhost:$SERVER_PORT/$prefix/$endpointId/${item.id}")
            } else item
        }
    }

    fun applyDetail(detail: EntryDetail, protocol: String): EntryDetail {
        val providesArt = OpenTuneProviderRegistryHolder.get().provider(protocol).providesArt
        if (providesArt) return detail
        return detail.copy(
            logo = if (detail.logo == null) "file:///android_asset/art/logo.png" else detail.logo,
            backdrop = if (detail.backdrop.isEmpty()) listOf("file:///android_asset/art/backdrop.png") else detail.backdrop,
        )
    }
}
