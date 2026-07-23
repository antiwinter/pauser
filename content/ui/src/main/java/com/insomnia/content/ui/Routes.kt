package com.insomnia.content.ui

import android.net.Uri
import com.insomnia.content.contract.EntryInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import com.insomnia.content.ui.catalog.browse.QuerySpec

private val navJson = Json { ignoreUnknownKeys = true }

fun QuerySpec.toJson(): String = navJson.encodeToString(this)


fun List<QuerySpec>.toJson(): String = navJson.encodeToString(this)

fun decodeQuerySpec(json: String): QuerySpec? =
    runCatching { navJson.decodeFromString<QuerySpec>(json) }.getOrNull()

fun decodeQuerySpecList(json: String): List<QuerySpec>? =
    runCatching { navJson.decodeFromString<List<QuerySpec>>(json) }.getOrNull()

fun EntryInfo.toJson(): String = navJson.encodeToString(this)

// androidx.navigation decodes path arguments with Uri.decode, which only
// interprets %xx escapes — it leaves '+' literal. URLEncoder.encode would
// emit '+' for spaces, so use Uri.encode (which emits %20) to round-trip.
object Routes {
    const val ADD_ENDPOINT = "add_endpoint"
    const val BROWSE = "browse?querySpecJson={querySpecJson}"
    const val DETAIL = "detail/{endpointId}/{ref}"
    const val LIVE_PLAYER = "live_player/{endpointId}/{ref}"
    const val IMAGE_VIEWER = "image_viewer/{endpointId}/{itemRef}"
    const val AUDIO_UNSUPPORTED = "audio_unsupported"
    const val PROVIDER_EDIT = "provider_edit/{protocol}?endpointId={endpointId}"
    const val SETTINGS = "settings"
    fun browse(specs: List<QuerySpec>) =
        "browse?querySpecJson=${Uri.encode(specs.toJson())}"

    fun providerEdit(protocol: String, endpointId: String? = null) =
        if (endpointId != null) "provider_edit/$protocol?endpointId=${Uri.encode(endpointId)}"
        else "provider_edit/$protocol"
    fun detail(endpointId: String, entry: EntryInfo) =
        "detail/${Uri.encode(endpointId)}/${Uri.encode(entry.ref)}"
    fun livePlayer(endpointId: String, entry: EntryInfo) =
        "live_player/${Uri.encode(endpointId)}/${Uri.encode(entry.ref)}"
    fun imageViewer(endpointId: String, itemRef: String) =
        "image_viewer/${Uri.encode(endpointId)}/${Uri.encode(itemRef)}"
}
