package com.opentune.emby.api

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private val CACHE_JSON = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = false
}

private const val LOG_TAG = "OT_EmbyCache"

/**
 * Stores raw Emby browse responses as a single nested tree.
 * Root is `{ __root__: { items: [ {...raw item...}, ... ] } }`.
 *
 * When a child is expanded, its raw items are nested under the parent item's
 * `"children": { items: [...], children: { childId: {}, ... } }` field.
 */
class EmbyBrowseCache(
    private val context: Context,
    private val baseUrl: String,
    private val accessToken: String,
) {
    private val cacheFile: File
        get() = File(context.filesDir, "emby_browse/emby_browse.json").also {
            it.parentFile?.mkdirs()
        }

    // Image downloads directory
    private val imageDir: File
        get() = File(context.filesDir, "emby_browse/images").also { it.mkdirs() }

    private val imageClient = OkHttpClient()

    /**
     * Save raw API response. [rawJson] is the full response body
     * (e.g. `{"Items": [...], "TotalRecordCount": N, ...}`).
     * Also downloads images for items whose Type is "Movie".
     * [parentDetailJson] is the detail of the parent folder being browsed into.
     * [itemDetailJsonMap] maps child item Id → raw detail JSON for items needing detail enrichment.
     */
    suspend fun setItemsFromRaw(
        parentId: String,
        rawJson: String,
        parentDetailJson: String? = null,
        itemDetailJsonMap: Map<String, String> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "setItemsFromRaw: parentId=$parentId detailCount=${itemDetailJsonMap.size}")
        val root = loadOrCreateRoot()
        val items = extractItemsArray(rawJson)
        if (items == null) {
            Log.e(LOG_TAG, "setItemsFromRaw: failed to extract Items array from raw JSON")
            return@withContext
        }

        // Download images for Movie items
        downloadMovieImages(items)

        if (parentId == "__root__") {
            val node = buildJsonObject {
                put("items", items)
                put("children", JsonObject(emptyMap()))
            }
            save(JsonObject(root + ("__root__" to node)))
        } else {
            // Merge detail into the parent item
            var newRoot = mergeChildIntoParent(root, parentId, items)
            if (parentDetailJson != null) {
                newRoot = mergeDetailIntoItem(newRoot, parentId, parentDetailJson)
            }
            // Merge detail into child items (e.g. Movies that need Overview)
            for ((itemId, detailJson) in itemDetailJsonMap) {
                newRoot = mergeDetailIntoItem(newRoot, itemId, detailJson)
            }
            save(newRoot)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        cacheFile.delete()
    }

    private fun extractItemsArray(rawJson: String): JsonArray? {
        return runCatching {
            val element = CACHE_JSON.parseToJsonElement(rawJson)
            (element as? JsonObject)?.get("Items") as? JsonArray
        }.getOrNull()
    }

    private fun loadOrCreateRoot(): JsonObject {
        return try {
            val element = CACHE_JSON.parseToJsonElement(cacheFile.readText())
            val obj = element.jsonObject
            Log.d(LOG_TAG, "loadOrCreateRoot: loaded existing tree, ${obj.keys.size} entries")
            obj
        } catch (e: Exception) {
            Log.d(LOG_TAG, "loadOrCreateRoot: no existing data or parse error, starting fresh")
            JsonObject(emptyMap())
        }
    }

    /**
     * Recursively finds the item with matching Id anywhere in the tree, then replaces
     * its "children" field with the fetched child data.
     */
    private fun mergeChildIntoParent(
        root: JsonObject,
        childId: String,
        childItems: JsonArray,
    ): JsonObject {
        val rootData = root["__root__"] as? JsonObject
        if (rootData == null) {
            return buildJsonObject {
                root.forEach { (k, v) -> put(k, v) }
                put(childId, buildChildNode(childItems))
            }
        }

        val itemsArray = rootData["items"] as? JsonArray
        if (itemsArray == null) {
            return buildJsonObject {
                root.forEach { (k, v) -> put(k, v) }
                put(childId, buildChildNode(childItems))
            }
        }

        val (newArray, found) = tryInsertChildren(itemsArray, childId, childItems)
        if (!found) {
            Log.w(LOG_TAG, "mergeChildIntoParent: parentId=$childId not found in tree")
            return buildJsonObject {
                root.forEach { (k, v) -> put(k, v) }
                put(childId, buildChildNode(childItems))
            }
        }

        return buildJsonObject {
            root.forEach { (k, v) ->
                if (k == "__root__") {
                    put("__root__", buildJsonObject {
                        rootData.forEach { (rk, rv) ->
                            if (rk == "items") put("items", newArray)
                            else put(rk, rv)
                        }
                    })
                } else {
                    put(k, v)
                }
            }
        }
    }

    /**
     * Recursively searches the items array for an item matching [childId].
     * When found, replaces its "children" field with [childItems].
     * Returns (newArray, true) if found and changed, (originalArray, false) if not.
     */
    private fun tryInsertChildren(
        itemsArray: JsonArray,
        childId: String,
        childItems: JsonArray,
    ): Pair<JsonArray, Boolean> {
        for (i in itemsArray.indices) {
            val item = itemsArray[i] as? JsonObject ?: continue
            val id = (item["Id"] as? kotlinx.serialization.json.JsonPrimitive)?.content

            if (id == childId) {
                // Found the parent — merge children
                val newItem = buildJsonObject {
                    item.forEach { (k, v) ->
                        if (k != "children") put(k, v)
                    }
                    put("children", buildChildNode(childItems))
                }
                return JsonArray(itemsArray.toMutableList().apply { this[i] = newItem }) to true
            }

            // Recurse into this item's children
            val childrenObj = item["children"] as? JsonObject
            if (childrenObj != null) {
                val existingChildItems = childrenObj["items"] as? JsonArray
                if (existingChildItems != null) {
                    val (newChildItems, changed) = tryInsertChildren(existingChildItems, childId, childItems)
                    if (changed) {
                        val updatedItem = buildJsonObject {
                            item.forEach { (k, v) ->
                                if (k == "children") {
                                    put("children", buildJsonObject {
                                        childrenObj.forEach { (ck, cv) ->
                                            if (ck == "items") put("items", newChildItems)
                                            else put(ck, cv)
                                        }
                                    })
                                } else put(k, v)
                            }
                        }
                        return JsonArray(itemsArray.toMutableList().apply { this[i] = updatedItem }) to true
                    }
                }
            }
        }
        return itemsArray to false
    }

    private fun buildChildNode(items: JsonArray): JsonObject = buildJsonObject {
        put("items", JsonArray(items.map { item ->
            // Wrap each item with an empty children placeholder
            val obj = item.jsonObject
            buildJsonObject {
                obj.forEach { (k, v) -> put(k, v) }
                put("children", JsonObject(emptyMap()))
            }
        }))
        // Extract child IDs for the placeholder map
        val childIds = items.mapNotNull { it.jsonObject["Id"] }
            .map { (it as kotlinx.serialization.json.JsonPrimitive).content }
            .toSet()
        put("children", JsonObject(childIds.associateWith { JsonObject(emptyMap()) }))
    }

    /**
     * Recursively finds an item by Id anywhere in the tree and merges its detail.
     * Returns the new root with the detail merged in.
     */
    private fun mergeDetailIntoItem(
        root: JsonObject,
        itemId: String,
        detailJson: String,
    ): JsonObject {
        val detailObj = runCatching {
            CACHE_JSON.parseToJsonElement(detailJson).jsonObject
        }.getOrNull() ?: return root

        val newRoot = deepMergeDetail(root, itemId, detailObj)
        return if (newRoot === root) {
            Log.w(LOG_TAG, "mergeDetailIntoItem: itemId=$itemId not found in tree")
            root
        } else {
            newRoot
        }
    }

    /**
     * Recursively walks the tree, returns a new root with detail merged in.
     * Returns the same reference if no change was needed.
     */
    private fun deepMergeDetail(root: JsonObject, itemId: String, detailObj: JsonObject): JsonObject {
        val rootData = root["__root__"] as? JsonObject
        if (rootData != null) {
            val itemsArray = rootData["items"] as? JsonArray
            if (itemsArray != null) {
                val (newArray, changed) = tryMergeItemsArray(itemsArray, itemId, detailObj)
                if (changed) {
                    return buildJsonObject {
                        root.forEach { (k, v) ->
                            if (k == "__root__") {
                                put("__root__", buildJsonObject {
                                    rootData.forEach { (rk, rv) ->
                                        if (rk == "items") put("items", newArray)
                                        else put(rk, rv)
                                    }
                                })
                            } else put(k, v)
                        }
                    }
                }
            }
        }

        // Fallback: check flat-map entries (for items stored outside __root__)
        for ((key, value) in root) {
            if (key == "__root__") continue
            val valueObj = value as? JsonObject ?: continue
            val childItems = valueObj["items"] as? JsonArray ?: continue
            val (newArray, changed) = tryMergeItemsArray(childItems, itemId, detailObj)
            if (changed) {
                return buildJsonObject {
                    root.forEach { (k, v) ->
                        if (k == key) {
                            put(k, buildJsonObject {
                                valueObj.forEach { (vk, vv) ->
                                    if (vk == "items") put("items", newArray)
                                    else put(vk, vv)
                                }
                            })
                        } else put(k, v)
                    }
                }
            }
        }

        return root // unchanged
    }

    /**
     * Tries to find itemId in the items array and merge detail. Also recurses into children.
     * Returns (newArray, true) if changed, (originalArray, false) if not.
     */
    private fun tryMergeItemsArray(
        itemsArray: JsonArray,
        itemId: String,
        detailObj: JsonObject,
    ): Pair<JsonArray, Boolean> {
        for (i in itemsArray.indices) {
            val item = itemsArray[i] as? JsonObject ?: continue
            val id = (item["Id"] as? kotlinx.serialization.json.JsonPrimitive)?.content

            if (id == itemId) {
                // Found it — merge detail
                val newItem = buildJsonObject {
                    item.forEach { (k, v) -> put(k, v) }
                    detailObj.forEach { (k, v) ->
                        if (!item.containsKey(k)) put(k, v)
                    }
                    put("detail", detailObj)
                }
                return JsonArray(itemsArray.toMutableList().apply { this[i] = newItem }) to true
            }

            // Recurse into children
            val childrenObj = item["children"] as? JsonObject
            if (childrenObj != null) {
                val childItems = childrenObj["items"] as? JsonArray
                if (childItems != null) {
                    val (newChildItems, changed) = tryMergeItemsArray(childItems, itemId, detailObj)
                    if (changed) {
                        val updatedItem = buildJsonObject {
                            item.forEach { (k, v) ->
                                if (k == "children") {
                                    put("children", buildJsonObject {
                                        childrenObj.forEach { (ck, cv) ->
                                            if (ck == "items") put("items", newChildItems)
                                            else put(ck, cv)
                                        }
                                    })
                                } else put(k, v)
                            }
                        }
                        return JsonArray(itemsArray.toMutableList().apply { this[i] = updatedItem }) to true
                    }
                }
            }
        }
        return itemsArray to false
    }

    /** Download all ImageTags and BackdropImageTags for items with Type="Movie". */
    private fun downloadMovieImages(items: JsonArray) {
        val normalized = EmbyClientFactory.normalizeBaseUrl(baseUrl).trimEnd('/')
        val apiKeyParam = "&api_key=$accessToken"

        for (item in items) {
            val obj = item.jsonObject
            val type = (obj["Type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val id = (obj["Id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
            if (type != "Movie") continue

            Log.d(LOG_TAG, "downloadMovieImages: downloading images for Movie id=$id")

            // Download ImageTags: Primary, Logo, Thumb, etc.
            val imageTags = obj["ImageTags"] as? JsonObject
            imageTags?.forEach { (typeName, tagElement) ->
                val tag = (tagElement as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach
                val url = "$normalized/Items/$id/Images/$typeName?tag=$tag$apiKeyParam"
                downloadImage(url, "Movie_$id/$typeName")
            }

            // Download BackdropImageTags: Backdrop/0, Backdrop/1, ...
            val backdropTags = obj["BackdropImageTags"] as? JsonArray
            backdropTags?.forEachIndexed { index, tagElement ->
                val tag = (tagElement as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEachIndexed
                val url = "$normalized/Items/$id/Images/Backdrop/$index?tag=$tag$apiKeyParam"
                downloadImage(url, "Movie_$id/Backdrop.$index")
            }
        }
    }

    /** Download an image URL and save to filesDir/emby_browse/images/<name>.<ext>. */
    private fun downloadImage(url: String, name: String) {
        val request = Request.Builder().url(url).build()
        val response = imageClient.newCall(request).execute()
        val body = response.body ?: return
        if (!response.isSuccessful) {
            Log.w(LOG_TAG, "downloadImage: HTTP ${response.code} for $url")
            return
        }

        // Infer extension from Content-Type
        val contentType = response.header("Content-Type") ?: "image/jpeg"
        val ext = when {
            contentType.contains("png", true) -> "png"
            contentType.contains("webp", true) -> "webp"
            contentType.contains("gif", true) -> "gif"
            else -> "jpg"
        }

        val file = File(imageDir, "$name.$ext").apply {
            parentFile?.mkdirs()
        }
        runCatching {
            file.outputStream().use { body.byteStream().copyTo(it) }
            Log.d(LOG_TAG, "downloadImage: saved ${file.path} (${file.length()} bytes)")
        }.onFailure { Log.e(LOG_TAG, "downloadImage: write failed for $name", it) }
    }

    private fun save(root: JsonObject) = runCatching {
        cacheFile.writeText(CACHE_JSON.encodeToString(JsonElement.serializer(), root))
    }.onSuccess { Log.d(LOG_TAG, "setItems: written OK, size=${cacheFile.length()}") }
        .onFailure { Log.e(LOG_TAG, "setItems: write failed", it) }
}
