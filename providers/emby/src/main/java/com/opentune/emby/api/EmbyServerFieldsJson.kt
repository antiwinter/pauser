package com.opentune.emby.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EmbyServerFieldsJson(
    val baseUrl: String,
    val userId: String,
    val accessToken: String,
    val serverId: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(fieldsJson: String): EmbyServerFieldsJson =
            json.decodeFromString<EmbyServerFieldsJson>(fieldsJson)

        fun encode(value: EmbyServerFieldsJson): String =
            json.encodeToString(EmbyServerFieldsJson.serializer(), value)
    }
}
