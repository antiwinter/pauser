package com.opentune.server

import android.util.Log
import com.opentune.genart.GenArt
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LOG_TAG = "GenartRoutes"

fun Application.installGenartRoutes(ctx: AppContext) {
    routing {
        get("/genart/{type}/{version}/{sourceId}/{itemId}") {
            val type = call.parameters["type"]
            val version = call.parameters["version"]
            val sourceId = call.parameters["sourceId"]
            val itemId = call.parameters["itemId"]

            if (type !in setOf("cover", "thumb") || version != GenArt.VERSION ||
                sourceId == null || itemId == null) {
                return@get call.respondBytes(
                    GenArt.transparentPlaceholder(),
                    ContentType.Image.PNG,
                )
            }

            val instance = ctx.getInstance(sourceId)
                ?: return@get call.respondBytes(
                    GenArt.transparentPlaceholder(),
                    ContentType.Image.PNG,
                )

            val bytes = withContext(Dispatchers.IO) {
                val spec = try {
                    instance.getPlaybackSpec(itemId, 0)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "getPlaybackSpec failed for $itemId", e)
                    null
                }

                try {
                    if (spec != null) {
                        GenArt.generateCover(spec.url, spec.headers)
                    } else {
                        null
                    }
                } finally {
                    spec?.hooks?.onDispose()
                }
            }

            call.respondBytes(
                bytes ?: GenArt.transparentPlaceholder(),
                ContentType.Image.PNG,
            )
        }
    }
}
