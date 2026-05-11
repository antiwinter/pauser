package com.opentune.emby

import com.opentune.emby.dto.AuthenticateByNameRequest
import com.opentune.emby.dto.AuthenticationResult
import com.opentune.emby.dto.BaseItemDto
import com.opentune.emby.dto.PlaybackInfoRequest
import com.opentune.emby.dto.PlaybackInfoResponse
import com.opentune.emby.dto.PlaybackProgressInfo
import com.opentune.emby.dto.PlaybackStartInfo
import com.opentune.emby.dto.PlaybackStopInfo
import com.opentune.emby.dto.QueryResultBaseItemDto
import com.opentune.emby.dto.SystemInfoDto
import com.opentune.emby.dto.UserDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

object EmbyFieldSets {
    const val BROWSE_FIELDS =
        "UserData,CommunityRating,ImageTags,BackdropImageTags,IndexNumber,OriginalTitle"
    const val DETAIL_FIELDS =
        "Overview,ImageTags,BackdropImageTags,RunTimeTicks,UserData,MediaSources,CommunityRating,Genres,Studios,ProductionYear,ProviderIds,ExternalUrls,OriginalTitle,IndexNumber,Etag,MediaStreams"
}

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
        @Query("SearchTerm") searchTerm: String? = null,
        @Query("SortBy") sortBy: String? = null,
        @Query("StartIndex") startIndex: Int? = null,
        @Query("Limit") limit: Int? = null,
        @Query("Fields") fields: String? = null,
    ): QueryResultBaseItemDto

    @GET("Users/{userId}/Items/{itemId}")
    suspend fun getItem(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
        @Query("Fields") fields: String? = null,
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
