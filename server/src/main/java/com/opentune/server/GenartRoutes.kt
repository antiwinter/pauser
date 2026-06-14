package com.opentune.server

import android.util.Log
import com.opentune.genart.GenArt
import com.opentune.player.EntryStateKeys
import com.opentune.player.PlayingState
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
        get("/genart/{type}/{version}/{endpointId}/{itemRef}") {
            val type = call.parameters["type"]
            val version = call.parameters["version"]
            val endpointId = call.parameters["endpointId"]
            val itemRef = call.parameters["itemRef"]

            if (type !in setOf("cover", "thumb") || version != GenArt.VERSION ||
                endpointId == null || itemRef == null) {
                return@get call.respondBytes(
                    GenArt.transparentPlaceholder(),
                    ContentType.Image.PNG,
                )
            }

            val client = ctx.getClient(endpointId)
                ?: return@get call.respondBytes(
                    GenArt.transparentPlaceholder(),
                    ContentType.Image.PNG,
                )

            val bytes = withContext(Dispatchers.IO) {
                val spec = try {
                    client.getPlaybackSpec(itemRef, 0)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "getPlaybackSpec failed for $itemRef", e)
                    null
                }

                try {
                    if (spec != null) {
                        GenArt.generateCover(spec.url, spec.headers)
                    } else {
                        null
                    }
                } finally {
                    if (spec != null) {
                        client.updateEntryState(
                            itemRef,
                            EntryStateKeys.PLAYING_STATE,
                            PlayingState.STOPPED.name,
                        )
                    }
                }
            }

            call.respondBytes(
                bytes ?: GenArt.transparentPlaceholder(),
                ContentType.Image.PNG,
            )
        }
    }
}
