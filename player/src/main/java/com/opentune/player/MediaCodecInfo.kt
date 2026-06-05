package com.opentune.player

import kotlinx.serialization.Serializable

/**
 * Media codec information included in [PlaybackSpec] and [com.opentune.content.contract.EntryInfo].
 * info[0].codec is always the video codec (used on detail screen).
 */
@Serializable
data class MediaCodecInfo(
    val codec: String,
    val bitDepth: Int? = null,
    val profile: String? = null,
)
