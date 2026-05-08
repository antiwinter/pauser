package com.opentune.emby.api

import android.content.Context
import com.opentune.emby.api.dto.BaseItemDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.io.File

private val CACHE_JSON = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = false
}

class EmbyBrowseCache(
    private val context: Context,
    private val serverId: String,
) {
    private val cacheFile: File
        get() = File(context.filesDir, "emby_browse/emby_browse_$serverId.json").also {
            it.parentFile?.mkdirs()
        }

    suspend fun setItems(parentId: String, items: List<BaseItemDto>) = withContext(Dispatchers.IO) {
        val root = loadOrCreateRoot()
        val childIds = items.mapNotNull { it.id }.toSet()

        val node = buildJsonObject {
            put("items", JsonArray(items.map { encodeItem(it) }))
            put("children", JsonObject(childIds.associateWith { JsonObject(emptyMap()) }))
        }

        val newRoot = JsonObject(root + (parentId to node))
        cacheFile.writeText(CACHE_JSON.encodeToString(JsonElement.serializer(), newRoot))
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        cacheFile.delete()
    }

    private fun loadOrCreateRoot(): JsonObject {
        return try {
            val element = CACHE_JSON.parseToJsonElement(cacheFile.readText())
            element as JsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
    }

    private fun encodeItem(item: BaseItemDto): JsonElement = try {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        json.encodeToJsonElement(BaseItemDto.serializer(), item)
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}
