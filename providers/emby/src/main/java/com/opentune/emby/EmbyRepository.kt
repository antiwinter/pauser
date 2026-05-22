package com.opentune.emby

import com.opentune.emby.dto.BaseItemDto
import com.opentune.emby.dto.DeviceProfile
import com.opentune.emby.dto.PlaybackInfoRequest
import com.opentune.emby.dto.PlaybackInfoResponse
import com.opentune.emby.dto.QueryResultBaseItemDto

class EmbyRepository(
    private val api: EmbyApi,
    private val userId: String,
    private val deviceProfile: DeviceProfile,
) {
    suspend fun systemInfo() = api.getSystemInfo()

    suspend fun getViews(): QueryResultBaseItemDto = api.getViews(userId)

    suspend fun getItems(
        parentId: String? = null,
        includeItemTypes: String? = null,
        excludeItemTypes: String? = null,
        recursive: Boolean = false,
        searchTerm: String? = null,
        sortBy: String? = null,
        sortOrder: String? = null,
        startIndex: Int? = null,
        limit: Int? = null,
        fields: String? = null,
        years: String? = null,
        genres: String? = null,
        studios: String? = null,
        countries: String? = null,
        filters: String? = null,
        ids: String? = null,
    ): QueryResultBaseItemDto = api.getItems(
        userId = userId,
        parentId = parentId,
        includeItemTypes = includeItemTypes,
        excludeItemTypes = excludeItemTypes,
        recursive = recursive,
        searchTerm = searchTerm,
        sortBy = sortBy ?: "SortName",
        sortOrder = sortOrder,
        startIndex = startIndex,
        limit = limit,
        fields = fields,
        years = years,
        genres = genres,
        studios = studios,
        countries = countries,
        filters = filters,
        ids = ids,
    )

    suspend fun markFavorite(itemId: String) = api.markFavorite(userId, itemId)
    suspend fun unmarkFavorite(itemId: String) = api.unmarkFavorite(userId, itemId)
    suspend fun markPlayed(itemId: String) = api.markPlayed(userId, itemId)
    suspend fun unmarkPlayed(itemId: String) = api.unmarkPlayed(userId, itemId)

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

    suspend fun reportPlaying(body: com.opentune.emby.dto.PlaybackStartInfo) = api.reportPlaying(body)

    suspend fun reportProgress(body: com.opentune.emby.dto.PlaybackProgressInfo) = api.reportProgress(body)

    suspend fun reportStopped(body: com.opentune.emby.dto.PlaybackStopInfo) = api.reportStopped(body)
}
