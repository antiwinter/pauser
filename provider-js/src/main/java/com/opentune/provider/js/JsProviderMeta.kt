package com.opentune.provider.js

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sidecar metadata file alongside each JS provider bundle.
 * Convention: `emby-provider.js` → `emby-provider.meta.json`
 */
@Serializable
data class JsProviderMeta(
    @SerialName("providerType")  val providerType: String,
    @SerialName("providesCover") val providesCover: Boolean,
)
