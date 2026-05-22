package com.opentune.emby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EmbyServerFieldsJson(
    @SerialName("base_url") val baseUrl: String,
    @SerialName("user_id") val userId: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("server_id") val serverId: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(fieldsJson: String): EmbyServerFieldsJson =
            json.decodeFromString<EmbyServerFieldsJson>(fieldsJson)

        fun encode(value: EmbyServerFieldsJson): String =
            json.encodeToString(EmbyServerFieldsJson.serializer(), value)
    }
}
