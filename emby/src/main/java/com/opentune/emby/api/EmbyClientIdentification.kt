package com.opentune.emby.api

/**
 * Jellyfin and Emby expect a MediaBrowser-style client header on API calls (including
 * [com.opentune.emby.api.EmbyApi.authenticateByName]); without it the server returns 400
 * ("Value cannot be null. (Parameter 'appName')").
 *
 * See Jellyfin authentication docs: `Authorization: MediaBrowser Client="…", Device="…", …`.
 */
data class EmbyClientIdentification(
    val clientName: String,
    val deviceName: String,
    val deviceId: String,
    val clientVersion: String,
) {
    fun mediaBrowserAuthorizationHeader(): String {
        fun quoted(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return buildString {
            append("MediaBrowser ")
            append("Client=").append(quoted(clientName)).append(", ")
            append("Device=").append(quoted(deviceName)).append(", ")
            append("DeviceId=").append(quoted(deviceId)).append(", ")
            append("Version=").append(quoted(clientVersion))
        }
    }

    companion object {
        fun fallback(): EmbyClientIdentification = EmbyClientIdentification(
            clientName = "OpenTune",
            deviceName = "Android",
            deviceId = "opentune-fallback-device",
            clientVersion = "0.0",
        )
    }
}

object EmbyClientIdentificationStore {
    @Volatile
    private var value: EmbyClientIdentification? = null

    fun install(identification: EmbyClientIdentification) {
        value = identification
    }

    fun current(): EmbyClientIdentification = value ?: EmbyClientIdentification.fallback()
}
