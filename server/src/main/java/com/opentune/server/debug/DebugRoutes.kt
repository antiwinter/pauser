package com.opentune.server.debug

import android.util.Log
import com.opentune.server.AppContext
import com.opentune.storage.ServerEntity
import com.opentune.storage.SubtitlePrefs
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val LOG_TAG = "OT_DebugRoutes"
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun Application.installDebugRoutes(ctx: AppContext) {
    routing {
        route("/providers") {
            get {
                val dtos = ctx.getProviders().map { p ->
                    ProviderDto(
                        protocol = p.protocol,
                        providesArt = p.providesArt,
                        fields = p.getFieldsSpec().map { f ->
                            FieldDto(
                                id = f.id,
                                labelKey = f.labelKey,
                                kind = f.kind.name,
                                required = f.required,
                                sensitive = f.sensitive,
                            )
                        },
                    )
                }
                call.respondText(json.encodeToString(dtos), ContentType.Application.Json)
            }
        }

        route("/servers") {
            get {
                val servers = ctx.serverDao.observeAll().first()
                val dtos = servers.map { s -> ServerDto(s.sourceId, s.protocol, s.displayName) }
                call.respondText(json.encodeToString(dtos), ContentType.Application.Json)
            }
            post {
                val body = runCatching { json.decodeFromString<AddServerRequest>(call.receiveText()) }.getOrNull()
                if (body == null) {
                    call.respondText(
                        json.encodeToString(AddServerResponse(error = "invalid request body")),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }
                val provider = ctx.getProvider(body.protocol)
                if (provider == null) {
                    call.respondText(
                        json.encodeToString(AddServerResponse(error = "unknown protocol: ${body.protocol}")),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }
                val result = runCatching { provider.validateFields(body.fields) }.getOrElse {
                    Log.e(LOG_TAG, "validateFields failed", it)
                    com.opentune.provider.ValidationResult.Error(it.message ?: "validation failed")
                }
                when (result) {
                    is com.opentune.provider.ValidationResult.Error -> {
                        call.respondText(
                            json.encodeToString(AddServerResponse(error = result.message)),
                            ContentType.Application.Json,
                            HttpStatusCode.UnprocessableEntity,
                        )
                    }
                    is com.opentune.provider.ValidationResult.Success -> {
                        val sourceId = "${body.protocol}_${result.hash}"
                        val now = System.currentTimeMillis()
                        val entity = ServerEntity(
                            sourceId = sourceId,
                            protocol = body.protocol,
                            displayName = result.name,
                            fieldsJson = Json.encodeToString(result.fields),
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now,
                        )
                        runCatching { ctx.serverDao.insert(entity) }.onFailure {
                            Log.w(LOG_TAG, "insert failed (may already exist): ${it.message}")
                        }
                        ctx.createAndRegister(sourceId, entity)
                        Log.i(LOG_TAG, "added server $sourceId (${result.name})")
                        call.respondText(
                            json.encodeToString(AddServerResponse(sourceId = sourceId, displayName = result.name)),
                            ContentType.Application.Json,
                            HttpStatusCode.Created,
                        )
                    }
                }
            }
        }

        route("/instances") {
            get {
                val servers = ctx.serverDao.observeAll().first()
                val dtos = servers.map { s -> ServerDto(s.sourceId, s.protocol, s.displayName) }
                call.respondText(json.encodeToString(dtos), ContentType.Application.Json)
            }

            get("/{sourceId}/browse") {
                val sourceId = call.parameters["sourceId"] ?: return@get call.respond400("missing sourceId")
                val location = call.request.queryParameters["location"]
                val start = call.request.queryParameters["start"]?.toIntOrNull() ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val instance = ctx.getInstance(sourceId) ?: return@get call.respond404("unknown sourceId")
                val result = runCatching { instance.listEntry(location, start, limit) }.getOrElse {
                    Log.e(LOG_TAG, "listEntry error", it); return@get call.respond500(it.message)
                }
                val dto = EntryListDto(
                    items = result.items.map { e ->
                        EntryInfoDto(ref = e.id, title = e.title, type = e.type.name, cover = e.cover)
                    },
                    totalCount = result.totalCount,
                )
                call.respondText(json.encodeToString(dto), ContentType.Application.Json)
            }

            get("/{sourceId}/detail") {
                val sourceId = call.parameters["sourceId"] ?: return@get call.respond400("missing sourceId")
                val ref = call.request.queryParameters["ref"] ?: return@get call.respond400("missing ref")
                val instance = ctx.getInstance(sourceId) ?: return@get call.respond404("unknown sourceId")
                val detail = runCatching { instance.getDetail(ref) }.getOrElse {
                    Log.e(LOG_TAG, "getDetail error", it); return@get call.respond500(it.message)
                }
                val map = buildMap<String, kotlinx.serialization.json.JsonElement> {
                    put("title", kotlinx.serialization.json.JsonPrimitive(detail.title))
                    put("overview", if (detail.overview != null) kotlinx.serialization.json.JsonPrimitive(detail.overview) else kotlinx.serialization.json.JsonNull)
                    put("isMedia", kotlinx.serialization.json.JsonPrimitive(detail.isMedia))
                    put("rating", if (detail.rating != null) kotlinx.serialization.json.JsonPrimitive(detail.rating) else kotlinx.serialization.json.JsonNull)
                    put("year", if (detail.year != null) kotlinx.serialization.json.JsonPrimitive(detail.year) else kotlinx.serialization.json.JsonNull)
                    put("backdrop", kotlinx.serialization.json.JsonArray(detail.backdrop.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                }
                call.respondText(
                    kotlinx.serialization.json.JsonObject(map).toString(),
                    ContentType.Application.Json,
                )
            }

            get("/{sourceId}/search") {
                val sourceId = call.parameters["sourceId"] ?: return@get call.respond400("missing sourceId")
                val scope = call.request.queryParameters["scope"] ?: ""
                val query = call.request.queryParameters["q"] ?: return@get call.respond400("missing q")
                val instance = ctx.getInstance(sourceId) ?: return@get call.respond404("unknown sourceId")
                val results = runCatching { instance.search(scope, query) }.getOrElse {
                    Log.e(LOG_TAG, "search error", it); return@get call.respond500(it.message)
                }
                val dtos = results.map { e ->
                    EntryInfoDto(ref = e.id, title = e.title, type = e.type.name, cover = e.cover)
                }
                call.respondText(json.encodeToString(dtos), ContentType.Application.Json)
            }

            get("/{sourceId}/playback") {
                val sourceId = call.parameters["sourceId"] ?: return@get call.respond400("missing sourceId")
                val ref = call.request.queryParameters["ref"] ?: return@get call.respond400("missing ref")
                val startMs = call.request.queryParameters["startMs"]?.toLongOrNull() ?: 0L
                val instance = ctx.getInstance(sourceId) ?: return@get call.respond404("unknown sourceId")
                val spec = runCatching { instance.getPlaybackSpec(ref, startMs) }.getOrElse {
                    Log.e(LOG_TAG, "getPlaybackSpec error", it); return@get call.respond500(it.message)
                }
                val dto = PlaybackSpecDto(
                    url = spec.url,
                    mimeType = spec.mimeType,
                    title = spec.title,
                    durationMs = spec.durationMs,
                    headers = spec.headers,
                )
                call.respondText(json.encodeToString(dto), ContentType.Application.Json)
            }
        }

        post("/navigate") {
            val body = runCatching { json.decodeFromString<NavigateRequest>(call.receiveText()) }.getOrNull()
            if (body == null) {
                call.respond400("invalid request body"); return@post
            }
            val cmd: NavCommand = when (body.route) {
                "home" -> NavCommand.Home
                "browse" -> {
                    val p = body.provider ?: return@post call.respond400("missing provider")
                    val s = body.sourceId ?: return@post call.respond400("missing sourceId")
                    NavCommand.Browse(p, s, body.itemRef)
                }
                "detail" -> {
                    val p = body.provider ?: return@post call.respond400("missing provider")
                    val s = body.sourceId ?: return@post call.respond400("missing sourceId")
                    val r = body.itemRef ?: return@post call.respond400("missing itemRef")
                    NavCommand.Detail(p, s, r)
                }
                "player" -> {
                    val p = body.provider ?: return@post call.respond400("missing provider")
                    val s = body.sourceId ?: return@post call.respond400("missing sourceId")
                    val r = body.itemRef ?: return@post call.respond400("missing itemRef")
                    NavCommand.Player(p, s, r, body.startMs)
                }
                "image" -> {
                    val p = body.provider ?: return@post call.respond400("missing provider")
                    val s = body.sourceId ?: return@post call.respond400("missing sourceId")
                    val r = body.itemRef ?: return@post call.respond400("missing itemRef")
                    NavCommand.Image(p, s, r)
                }
                else -> return@post call.respond400("unknown route: ${body.route}")
            }
            NavigationBridge.commands.trySend(cmd)
            Log.i(LOG_TAG, "navigate command sent: $cmd")
            call.respondText(json.encodeToString(OkResponse()), ContentType.Application.Json)
        }

        // --- Debug routes for media state ---
        route("/debug") {
            route("/subtitle-prefs") {
                get {
                    val prefs = ctx.appConfigStore.loadSubtitlePrefs()
                    call.respondText(
                        json.encodeToString(SubtitlePrefsDto(prefs.offsetFraction, prefs.sizeScale)),
                        ContentType.Application.Json,
                    )
                }
                post {
                    val body = runCatching { json.decodeFromString<SubtitlePrefsDto>(call.receiveText()) }.getOrNull()
                    if (body == null) {
                        call.respond400("invalid request body"); return@post
                    }
                    ctx.appConfigStore.saveSubtitlePrefs(SubtitlePrefs(body.offsetFraction, body.sizeScale))
                    call.respondText(json.encodeToString(body), ContentType.Application.Json)
                }
            }

            route("/media-state") {
                get {
                    val protocol = call.request.queryParameters["protocol"] ?: return@get call.respond400("missing protocol")
                    val sourceId = call.request.queryParameters["sourceId"] ?: return@get call.respond400("missing sourceId")
                    val all = ctx.mediaStateStore.observeForSource(protocol, sourceId).first()
                    val dtos = all.map { it.toDto() }
                    call.respondText(json.encodeToString(dtos), ContentType.Application.Json)
                }

                get("/{protocol}/{sourceId}/{itemId}") {
                    val protocol = call.parameters["protocol"] ?: return@get call.respond400("missing protocol")
                    val sourceId = call.parameters["sourceId"] ?: return@get call.respond400("missing sourceId")
                    val itemId = call.parameters["itemId"] ?: return@get call.respond400("missing itemId")
                    val snapshot = ctx.mediaStateStore.get(protocol, sourceId, itemId)
                    if (snapshot == null) {
                        call.respond404("no state found for $protocol/$sourceId/$itemId")
                        return@get
                    }
                    call.respondText(json.encodeToString(snapshot.toDto()), ContentType.Application.Json)
                }

                post("/subtitle-track") {
                    val body = runCatching { json.decodeFromString<SetTrackRequest>(call.receiveText()) }.getOrNull()
                    if (body == null) {
                        call.respond400("invalid request body"); return@post
                    }
                    ctx.mediaStateStore.upsertSubtitleTrack(body.protocol, body.sourceId, body.itemId, body.trackId)
                    val snapshot = ctx.mediaStateStore.get(body.protocol, body.sourceId, body.itemId)
                    if (snapshot == null) {
                        call.respond500("state not found after upsert")
                        return@post
                    }
                    call.respondText(json.encodeToString(snapshot.toDto()), ContentType.Application.Json)
                }

                post("/audio-track") {
                    val body = runCatching { json.decodeFromString<SetTrackRequest>(call.receiveText()) }.getOrNull()
                    if (body == null) {
                        call.respond400("invalid request body"); return@post
                    }
                    ctx.mediaStateStore.upsertAudioTrack(body.protocol, body.sourceId, body.itemId, body.trackId)
                    val snapshot = ctx.mediaStateStore.get(body.protocol, body.sourceId, body.itemId)
                    if (snapshot == null) {
                        call.respond500("state not found after upsert")
                        return@post
                    }
                    call.respondText(json.encodeToString(snapshot.toDto()), ContentType.Application.Json)
                }
            }
        }
    }
}

// --- helpers ---

private suspend fun io.ktor.server.application.ApplicationCall.respond400(msg: String) {
    respondText(json.encodeToString(ErrorResponse(msg)), ContentType.Application.Json, HttpStatusCode.BadRequest)
}

private suspend fun io.ktor.server.application.ApplicationCall.respond404(msg: String) {
    respondText(json.encodeToString(ErrorResponse(msg)), ContentType.Application.Json, HttpStatusCode.NotFound)
}

private suspend fun io.ktor.server.application.ApplicationCall.respond500(msg: String?) {
    respondText(
        json.encodeToString(ErrorResponse(msg ?: "internal error")),
        ContentType.Application.Json,
        HttpStatusCode.InternalServerError,
    )
}
