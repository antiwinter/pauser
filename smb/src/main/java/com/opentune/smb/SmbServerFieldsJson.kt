package com.opentune.smb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SmbServerFieldsJson(
    val host: String,
    @SerialName("share_name") val shareName: String,
    val username: String,
    val password: String,
    val domain: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(fieldsJson: String): SmbServerFieldsJson =
            json.decodeFromString<SmbServerFieldsJson>(fieldsJson)

        fun encode(value: SmbServerFieldsJson): String =
            json.encodeToString(SmbServerFieldsJson.serializer(), value)
    }
}
