package com.opentune.server.debug

import com.opentune.storage.EntryStateEntity
import kotlinx.serialization.Serializable

@Serializable
data class ProviderDto(
    val protocol: String,
    val providesArt: Boolean,
    val fields: List<FieldDto>,
)

@Serializable
data class FieldDto(
    val id: String,
    val labelKey: String,
    val kind: String,
    val required: Boolean,
    val sensitive: Boolean,
)

@Serializable
data class ServerDto(
    val endpointId: String,
    val protocol: String,
    val displayName: String,
)

@Serializable
data class AddServerRequest(
    val protocol: String,
    val fields: Map<String, String>,
)

@Serializable
data class AddServerResponse(
    val endpointId: String? = null,
    val displayName: String? = null,
    val error: String? = null,
)

@Serializable
data class EntryInfoDto(
    val ref: String,
    val title: String,
    val type: String,
    val cover: String? = null,
)

@Serializable
data class EntryListDto(
    val items: List<EntryInfoDto>,
    val totalCount: Int,
)

@Serializable
data class PlaybackSpecDto(
    val url: String,
    val mimeType: String? = null,
    val title: String,
    val durationMs: Long? = null,
    val headers: Map<String, String>,
)

@Serializable
data class NavigateRequest(
    val route: String,
    val provider: String? = null,
    val endpointId: String? = null,
    val itemRef: String? = null,
    val startMs: Long = 0,
)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class EntryStateDto(
    val protocol: String,
    val endpointId: String,
    val itemId: String,
    val positionMs: Long,
    val playbackSpeed: Float,
    val selectedSubtitleTrackId: String?,
    val selectedAudioTrackId: String?,
    val title: String?,
    val type: String?,
    val isFavorite: Boolean,
)

fun EntryStateEntity.toDto(): EntryStateDto =
    EntryStateDto(
        protocol = protocol,
        endpointId = endpointId,
        itemId = itemId,
        positionMs = positionMs,
        playbackSpeed = playbackSpeed,
        selectedSubtitleTrackId = selectedSubtitleTrackId,
        selectedAudioTrackId = selectedAudioTrackId,
        title = title,
        type = type,
        isFavorite = isFavorite,
    )

@Serializable
data class SubtitlePrefsDto(
    val offsetFraction: Float,
    val sizeScale: Float,
)

@Serializable
data class SetTrackRequest(
    val protocol: String,
    val endpointId: String,
    val itemId: String,
    val trackId: String?,
)

@Serializable
data class JarRequest(
    val name: String,
    val args: String,  // raw JSON object — same shape as HostApis.handleJar argsJson
)

@Serializable
data class JarResponse(
    val result: String? = null,
    val error: String? = null,
)
