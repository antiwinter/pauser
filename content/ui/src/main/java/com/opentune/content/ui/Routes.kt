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
    const val BROWSE = "browse/{provider}/{endpointId}/{location}"
    const val DETAIL = "detail/{provider}/{endpointId}/{itemRef}/{infoJson}"
    const val PLAYER = "player/{provider}/{endpointId}/{itemRef}/{startMs}/{infoJson}"
    const val SEARCH = "search/{provider}/{endpointId}/{scopeLocation}"
    const val PROVIDER_ADD = "provider_add/{protocol}"
    const val PROVIDER_EDIT = "provider_edit/{protocol}/{endpointId}"
    const val PROXY_ADD = "proxy_add/{proxyType}"
    const val PROXY_EDIT = "proxy_edit/{proxyType}/{proxyId}"
    const val SETTINGS = "settings"
    const val IMAGE_VIEWER = "image_viewer/{provider}/{endpointId}/{itemRef}"

    fun providerAdd(protocol: String) = "provider_add/$protocol"
    fun providerEdit(protocol: String, endpointId: String) =
        "provider_edit/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}"
    fun proxyAdd(proxyType: String) = "proxy_add/$proxyType"
    fun proxyEdit(proxyType: String, proxyId: String) =
        "proxy_edit/$proxyType/${URLEncoder.encode(proxyId, UrlCharset)}"
    fun browse(protocol: String, endpointId: String, locationRaw: String) =
        "browse/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(locationRaw, UrlCharset)}"
    fun detail(protocol: String, endpointId: String, itemRefRaw: String, infoJson: String? = null) =
        "detail/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}/${URLEncoder.encode(infoJson ?: "", UrlCharset)}"
    fun player(protocol: String, endpointId: String, itemRefRaw: String, startMs: Long, info: EntryInfo? = null) =
        "player/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}/$startMs/${URLEncoder.encode(info?.toJson() ?: "", UrlCharset)}"
    fun search(protocol: String, endpointId: String, scopeLocationRaw: String) =
        "search/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(scopeLocationRaw, UrlCharset)}"
    fun imageViewer(protocol: String, endpointId: String, itemRefRaw: String) =
        "image_viewer/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}"
}
