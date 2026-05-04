package com.opentune.emby.api

import com.opentune.emby.api.dto.BaseItemDto

object EmbyImageUrls {

    fun primaryPoster(
        baseUrl: String,
        item: BaseItemDto,
        accessToken: String? = null,
        maxHeight: Int = 480,
    ): String? {
        val id = item.id ?: return null
        val tag = item.imageTags?.get("Primary") ?: return null
        val base = EmbyClientFactory.normalizeBaseUrl(baseUrl).trimEnd('/')
        val key = accessToken?.takeIf { it.isNotBlank() }?.let { "&api_key=${it}" } ?: ""
        return "$base/Items/$id/Images/Primary?maxHeight=$maxHeight&tag=$tag$key"
    }

    /** Smaller Primary image for grid cells. */
    fun primaryThumb(
        baseUrl: String,
        item: BaseItemDto,
        accessToken: String? = null,
        maxHeight: Int = 220,
    ): String? = primaryPoster(baseUrl, item, accessToken, maxHeight)
}
