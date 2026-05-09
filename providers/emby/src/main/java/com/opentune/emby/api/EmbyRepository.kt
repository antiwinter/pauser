package com.opentune.emby.api

import com.opentune.emby.api.dto.BaseItemDto
import com.opentune.emby.api.dto.DeviceProfile
import com.opentune.emby.api.dto.PlaybackInfoRequest
import com.opentune.emby.api.dto.PlaybackInfoResponse
import com.opentune.emby.api.dto.QueryResultBaseItemDto
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLEncoder

class EmbyRepository(
    baseUrl: String,
    private val userId: String,
    accessToken: String,
    private val deviceProfile: DeviceProfile,
) {
    private val api: EmbyApi = EmbyClientFactory.create(EmbyClientFactory.normalizeBaseUrl(baseUrl), accessToken)

    // Raw HTTP client for capturing full Emby response JSON (no Retrofit deserialization).
    private val rawClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val ident = EmbyClientIdentificationStore.current()
            val mediaBrowser = ident.mediaBrowserAuthorizationHeader()
            val req = chain.request().newBuilder()
                .header("Accept", "application/json")
                .header("Authorization", mediaBrowser)
                .header("X-Emby-Authorization", mediaBrowser)
                .header("X-Emby-Token", accessToken)
                .build()
            chain.proceed(req)
        }
        .build()

    private val normalizedUrl = EmbyClientFactory.normalizeBaseUrl(baseUrl)

    suspend fun systemInfo() = api.getSystemInfo()

    suspend fun getViews(): QueryResultBaseItemDto = api.getViews(userId)

    suspend fun getViewsRaw(): String {
        val url = "${normalizedUrl}Users/$userId/Views".toHttpUrl()
        return rawGet(url)
    }

    suspend fun getItems(
        parentId: String? = null,
        includeItemTypes: String? = null,
        recursive: Boolean = false,
        searchTerm: String? = null,
        startIndex: Int? = null,
        limit: Int? = null,
    ): QueryResultBaseItemDto = api.getItems(
        userId = userId,
        parentId = parentId,
        includeItemTypes = includeItemTypes,
        recursive = recursive,
        searchTerm = searchTerm,
        sortBy = "SortName",
        startIndex = startIndex,
        limit = limit,
    )

    suspend fun getItemsRaw(
        parentId: String? = null,
        includeItemTypes: String? = null,
        recursive: Boolean = false,
        searchTerm: String? = null,
        startIndex: Int? = null,
        limit: Int? = null,
    ): String {
        val url = "${normalizedUrl}Users/$userId/Items".toHttpUrl().newBuilder()
            .addQueryParameter("SortBy", "SortName")
            .apply {
                parentId?.let { addQueryParameter("ParentId", it) }
                includeItemTypes?.let { addQueryParameter("IncludeItemTypes", it) }
                addQueryParameter("Recursive", recursive.toString())
                searchTerm?.let { addQueryParameter("SearchTerm", it) }
                startIndex?.let { addQueryParameter("StartIndex", it.toString()) }
                limit?.let { addQueryParameter("Limit", it.toString()) }
            }
            .build()
        return rawGet(url)
    }

    private suspend fun rawGet(url: okhttp3.HttpUrl): String {
        val request = okhttp3.Request.Builder().url(url).build()
        val response = rawClient.newCall(request).execute()
        return response.body?.string()
            ?: throw RuntimeException("Empty response from $url")
    }

    suspend fun getItem(itemId: String): BaseItemDto = api.getItem(userId, itemId)

    suspend fun getItemRaw(itemId: String): String {
        val url = "${normalizedUrl}Users/$userId/Items/$itemId".toHttpUrl()
        return rawGet(url)
    }

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
