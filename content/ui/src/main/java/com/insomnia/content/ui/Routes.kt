package com.insomnia.content.ui

import android.net.Uri
import com.insomnia.content.contract.EntryInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val navJson = Json { ignoreUnknownKeys = true }

fun EntryInfo.toJson(): String = navJson.encodeToString(this)

fun decodeEntryInfo(json: String): EntryInfo? =
    runCatching { navJson.decodeFromString<EntryInfo>(json) }.getOrNull()

// androidx.navigation decodes path arguments with Uri.decode, which only
// interprets %xx escapes — it leaves '+' literal. URLEncoder.encode would
// emit '+' for spaces, so use Uri.encode (which emits %20) to round-trip.
object Routes {
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
        if (endpointId != null) "provider_edit/$protocol?endpointId=${Uri.encode(endpointId)}"
        else "provider_edit/$protocol"
    fun browse(endpointId: String, entry: EntryInfo) =
        "browse/${Uri.encode(endpointId)}/${Uri.encode(entry.ref)}"
    fun detail(endpointId: String, entry: EntryInfo) =
        "detail/${Uri.encode(endpointId)}/${Uri.encode(entry.ref)}"
    fun search(endpointId: String, scopeLocationRaw: String) =
        "search/${Uri.encode(endpointId)}/${Uri.encode(scopeLocationRaw)}"
    fun imageViewer(endpointId: String, itemRef: String) =
        "image_viewer/${Uri.encode(endpointId)}/${Uri.encode(itemRef)}"
}
