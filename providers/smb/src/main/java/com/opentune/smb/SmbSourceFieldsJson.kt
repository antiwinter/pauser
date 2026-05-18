package com.opentune.smb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SmbSourceFieldsJson(
    val host: String,
    @SerialName("share_name") val shareName: String,
    val username: String,
    val password: String,
    val domain: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(fieldsJson: String): SmbSourceFieldsJson =
            json.decodeFromString<SmbSourceFieldsJson>(fieldsJson)

        fun encode(value: SmbSourceFieldsJson): String =
            json.encodeToString(SmbSourceFieldsJson.serializer(), value)
    }
}
