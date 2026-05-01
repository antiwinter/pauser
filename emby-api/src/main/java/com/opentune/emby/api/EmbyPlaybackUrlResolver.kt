package com.opentune.emby.api

import com.opentune.emby.api.dto.MediaSourceInfo

object EmbyPlaybackUrlResolver {

    /**
     * Picks transcoding URL if present, otherwise direct stream URL.
     * Prefixes with [baseUrl] when the server returns a relative path.
     */
    fun resolve(baseUrl: String, source: MediaSourceInfo): String {
        val relative = source.transcodingUrl?.takeIf { it.isNotBlank() }
            ?: source.directStreamUrl?.takeIf { it.isNotBlank() }
            ?: error("No playback URL in MediaSourceInfo")
        val base = EmbyClientFactory.normalizeBaseUrl(baseUrl).trimEnd('/')
        return if (relative.startsWith("http://", ignoreCase = true) ||
            relative.startsWith("https://", ignoreCase = true)
        ) {
            relative
        } else {
            "$base/${relative.trimStart('/')}"
        }
    }

    fun playMethod(source: MediaSourceInfo): String = when {
        !source.transcodingUrl.isNullOrBlank() -> "Transcode"
        !source.directStreamUrl.isNullOrBlank() -> "DirectStream"
        else -> "DirectPlay"
    }
}
