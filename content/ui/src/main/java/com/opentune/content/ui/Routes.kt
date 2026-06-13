package com.opentune.content.ui

import com.opentune.content.contract.EntryInfo
import java.net.URLEncoder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val navJson = Json { ignoreUnknownKeys = true }

fun EntryInfo.toJson(): String = navJson.encodeToString(this)

fun decodeEntryInfo(json: String): EntryInfo? =
    runCatching { navJson.decodeFromString<EntryInfo>(json) }.getOrNull()

object Routes {
    private const val UrlCharset = "UTF-8"
    const val HOME = "home"
    const val ADD_ENDPOINT = "add_endpoint"
    const val BROWSE = "browse/{endpointId}/{ref}"
    const val DETAIL = "detail/{endpointId}/{ref}"
    const val SEARCH = "search/{endpointId}/{scopeLocation}"
    const val PROVIDER_EDIT = "provider_edit/{protocol}?endpointId={endpointId}"
    const val SETTINGS = "settings"
    const val IMAGE_VIEWER = "image_viewer/{endpointId}/{itemRef}"
    const val AUDIO_UNSUPPORTED = "audio_unsupported"

    fun providerEdit(protocol: String, endpointId: String? = null) =
        if (endpointId != null) "provider_edit/$protocol?endpointId=${URLEncoder.encode(endpointId, UrlCharset)}"
        else "provider_edit/$protocol"
    fun browse(endpointId: String, entry: EntryInfo) =
        "browse/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(entry.ref, UrlCharset)}"
    fun detail(endpointId: String, entry: EntryInfo) =
        "detail/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(entry.ref, UrlCharset)}"
    fun search(endpointId: String, scopeLocationRaw: String) =
        "search/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(scopeLocationRaw, UrlCharset)}"
    fun imageViewer(endpointId: String, itemRef: String) =
        "image_viewer/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(itemRef, UrlCharset)}"
}
