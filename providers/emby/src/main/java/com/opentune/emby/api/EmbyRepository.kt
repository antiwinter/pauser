package com.opentune.emby.api

import com.opentune.emby.api.dto.BaseItemDto
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.emby.api.dto.PlaybackInfoRequest
import com.opentune.emby.api.dto.PlaybackInfoResponse
import com.opentune.emby.api.dto.QueryResultBaseItemDto

class EmbyRepository(
    baseUrl: String,
    private val userId: String,
    accessToken: String,
    private val deviceProfile: DeviceProfile,
) {
    private val api: EmbyApi = EmbyClientFactory.create(EmbyClientFactory.normalizeBaseUrl(baseUrl), accessToken)

    suspend fun systemInfo() = api.getSystemInfo()

    suspend fun getViews(): QueryResultBaseItemDto = api.getViews(userId)

    suspend fun getItems(
        parentId: String? = null,
        includeItemTypes: String? = null,
        recursive: Boolean = false,
        searchTerm: String? = null,
        startIndex: Int? = null,
        limit: Int? = null,
        fields: String? = null,
    ): QueryResultBaseItemDto = api.getItems(
        userId = userId,
        parentId = parentId,
        includeItemTypes = includeItemTypes,
        recursive = recursive,
        searchTerm = searchTerm,
        sortBy = "SortName",
        startIndex = startIndex,
        limit = limit,
        fields = fields,
    )

    suspend fun getItem(itemId: String, fields: String? = null): BaseItemDto =
        api.getItem(userId, itemId, fields = fields)

    suspend fun getPlaybackInfo(
        itemId: String,
        startTimeTicks: Long? = null,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
    ): PlaybackInfoResponse {
        return api.getPlaybackInfo(
            itemId,
            PlaybackInfoRequest(
                id = itemId,
                userId = userId,
                maxStreamingBitrate = 120_000_000,
                startTimeTicks = startTimeTicks,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                deviceProfile = deviceProfile,
                enableDirectPlay = true,
                enableDirectStream = true,
                enableTranscoding = true,
            ),
        )
    }

    suspend fun reportPlaying(body: com.opentune.emby.api.dto.PlaybackStartInfo) = api.reportPlaying(body)

    suspend fun reportProgress(body: com.opentune.emby.api.dto.PlaybackProgressInfo) = api.reportProgress(body)

    suspend fun reportStopped(body: com.opentune.emby.api.dto.PlaybackStopInfo) = api.reportStopped(body)
}
