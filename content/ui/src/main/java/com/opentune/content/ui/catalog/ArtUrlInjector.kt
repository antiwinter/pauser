package com.opentune.content.ui.catalog

import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.OpenTuneProviderRegistryHolder
import com.opentune.content.contract.SERVER_PORT
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
                val encodedRef = URLEncoder.encode(item.ref, StandardCharsets.UTF_8.toString())
                item.copy(cover = "http://localhost:$SERVER_PORT/$prefix/$endpointId/$encodedRef")
            } else item
        }
    }

    fun applyInfo(info: EntryInfo, protocol: String): EntryInfo {
        val providesArt = OpenTuneProviderRegistryHolder.get().provider(protocol).providesArt
        if (providesArt) return info
        return info.copy(
            backdrop = if (info.backdrop.isEmpty()) listOf("file:///android_asset/art/backdrop.png") else info.backdrop,
        )
    }
}
