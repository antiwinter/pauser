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
    const val BROWSE = "browse/{provider}/{endpointId}/{infoJson}"
    const val DETAIL = "detail/{provider}/{endpointId}/{itemRef}/{infoJson}"
    const val PLAYER = "player/{provider}/{endpointId}/{itemRef}/{startMs}/{infoJson}"
    const val SEARCH = "search/{provider}/{endpointId}/{scopeLocation}"
    const val PROVIDER_EDIT = "provider_edit/{protocol}?endpointId={endpointId}"
    const val SETTINGS = "settings"
    const val IMAGE_VIEWER = "image_viewer/{provider}/{endpointId}/{itemRef}"
    const val AUDIO_UNSUPPORTED = "audio_unsupported"

    fun providerEdit(protocol: String, endpointId: String? = null) =
        if (endpointId != null) "provider_edit/$protocol?endpointId=${URLEncoder.encode(endpointId, UrlCharset)}"
        else "provider_edit/$protocol"
    fun browse(protocol: String, endpointId: String, entry: EntryInfo) =
        "browse/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(entry.toJson(), UrlCharset)}"
    fun detail(protocol: String, endpointId: String, itemRefRaw: String, infoJson: String? = null) =
        "detail/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}/${URLEncoder.encode(infoJson ?: "", UrlCharset)}"
    fun player(protocol: String, endpointId: String, itemRefRaw: String, startMs: Long, info: EntryInfo? = null) =
        "player/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}/$startMs/${URLEncoder.encode(info?.toJson() ?: "", UrlCharset)}"
    fun search(protocol: String, endpointId: String, scopeLocationRaw: String) =
        "search/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(scopeLocationRaw, UrlCharset)}"
    fun imageViewer(protocol: String, endpointId: String, itemRefRaw: String) =
        "image_viewer/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}"
}
