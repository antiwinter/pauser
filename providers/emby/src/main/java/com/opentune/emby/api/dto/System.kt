package com.opentune.emby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SystemInfoDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
)
