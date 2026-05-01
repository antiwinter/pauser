package com.opentune.emby.api

import com.opentune.emby.api.dto.AuthenticateByNameRequest
import com.opentune.emby.api.dto.AuthenticationResult
import com.opentune.emby.api.dto.BaseItemDto
import com.opentune.emby.api.dto.PlaybackInfoRequest
import com.opentune.emby.api.dto.PlaybackInfoResponse
import com.opentune.emby.api.dto.PlaybackProgressInfo
import com.opentune.emby.api.dto.PlaybackStartInfo
import com.opentune.emby.api.dto.PlaybackStopInfo
import com.opentune.emby.api.dto.QueryResultBaseItemDto
import com.opentune.emby.api.dto.SystemInfoDto
import com.opentune.emby.api.dto.UserDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface EmbyApi {

    @POST("Users/AuthenticateByName")
    suspend fun authenticateByName(@Body body: AuthenticateByNameRequest): AuthenticationResult

    @GET("Users/{userId}")
    suspend fun getUser(@Path("userId") userId: String): UserDto

    @GET("System/Info")
    suspend fun getSystemInfo(): SystemInfoDto

    @GET("Users/{userId}/Views")
    suspend fun getViews(@Path("userId") userId: String): QueryResultBaseItemDto

    @GET("Users/{userId}/Items")
    suspend fun getItems(
        @Path("userId") userId: String,
        @Query("ParentId") parentId: String? = null,
        @Query("IncludeItemTypes") includeItemTypes: String? = null,
        @Query("Recursive") recursive: Boolean? = null,
        @Query("SortBy") sortBy: String? = null,
        @Query("StartIndex") startIndex: Int? = null,
        @Query("Limit") limit: Int? = null,
    ): QueryResultBaseItemDto

    @GET("Users/{userId}/Items/{itemId}")
    suspend fun getItem(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
    ): BaseItemDto

    @POST("Items/{itemId}/PlaybackInfo")
    suspend fun getPlaybackInfo(
        @Path("itemId") itemId: String,
        @Body body: PlaybackInfoRequest,
    ): PlaybackInfoResponse

    @POST("Sessions/Playing")
    suspend fun reportPlaying(@Body body: PlaybackStartInfo)

    @POST("Sessions/Playing/Progress")
    suspend fun reportProgress(@Body body: PlaybackProgressInfo)

    @POST("Sessions/Playing/Stopped")
    suspend fun reportStopped(@Body body: PlaybackStopInfo)
}
