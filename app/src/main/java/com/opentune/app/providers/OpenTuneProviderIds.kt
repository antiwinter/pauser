package com.opentune.app.providers

/** Route / registry ids for catalog and server configuration. Literal values live only in this file. */
object OpenTuneProviderIds {
    const val HTTP_LIBRARY: String = "emby"
    const val FILE_SHARE: String = "smb"

    fun isKnown(providerId: String): Boolean =
        providerId == HTTP_LIBRARY || providerId == FILE_SHARE
}
