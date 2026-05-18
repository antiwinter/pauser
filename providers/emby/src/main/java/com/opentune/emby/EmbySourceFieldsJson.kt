package com.opentune.emby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EmbySourceFieldsJson(
    @SerialName("base_url") val baseUrl: String,
    @SerialName("user_id") val userId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("server_id") val serverId: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(fieldsJson: String): EmbySourceFieldsJson =
            json.decodeFromString<EmbySourceFieldsJson>(fieldsJson)

        fun encode(value: EmbySourceFieldsJson): String =
            json.encodeToString(EmbySourceFieldsJson.serializer(), value)
    }
}
