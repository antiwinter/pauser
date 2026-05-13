package com.opentune.emby

import com.opentune.emby.dto.BaseItemDto

object EmbyImageUrls {

    fun imageUrl(
        baseUrl: String,
        itemId: String,
        imageType: String,
        tag: String,
        accessToken: String?,
        maxHeight: Int = 220,
        index: Int? = null,
    ): String {
        val base = EmbyClientFactory.normalizeBaseUrl(baseUrl).trimEnd('/')
        val key = accessToken?.takeIf { it.isNotBlank() }?.let { "&api_key=$it" } ?: ""
        val indexSegment = if (index != null) "/$index" else ""
        return "$base/Items/$itemId/Images/$imageType$indexSegment?maxHeight=$maxHeight&tag=$tag$key"
    }
}
