package com.opentune.app.providers

import com.opentune.emby.api.EmbyServerFields
import com.opentune.provider.ServerFieldSpec
import com.opentune.smb.SmbServerFields

object ProviderFieldSchema {
    fun fieldsForAdd(providerId: String): List<ServerFieldSpec> = when (providerId) {
        OpenTuneProviderIds.HTTP_LIBRARY -> EmbyServerFields.serverAddFields()
        OpenTuneProviderIds.FILE_SHARE -> SmbServerFields.serverFields()
        else -> error("Unknown provider: $providerId")
    }

    fun fieldsForEdit(providerId: String): List<ServerFieldSpec> = when (providerId) {
        OpenTuneProviderIds.HTTP_LIBRARY -> EmbyServerFields.serverEditFields()
        OpenTuneProviderIds.FILE_SHARE -> SmbServerFields.serverFields()
        else -> error("Unknown provider: $providerId")
    }
}
