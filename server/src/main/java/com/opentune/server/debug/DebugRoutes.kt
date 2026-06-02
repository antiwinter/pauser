package com.opentune.server.debug

import android.util.Log
import com.opentune.server.AppContext
import com.opentune.content.contract.SearchQuery
import com.opentune.core.form.contract.QrResult
import com.opentune.storage.EndpointEntity
import com.opentune.storage.EntryStateKey
import com.opentune.storage.SubtitlePrefs
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
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

        route("/endpoints") {
            get {
                val servers = ctx.endpointDao.observeAll().first()
                val dtos = servers.map { s -> ServerDto(s.endpointId, s.protocol, s.displayName) }
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
                val client = runCatching {
                    provider.createClient(body.fields)
                }.getOrElse {
                    call.respondText(
                        json.encodeToString(AddServerResponse(error = it.message ?: "failed to create client")),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }
                val result = runCatching { client.test() }.getOrElse {
                    Log.e(LOG_TAG, "client.test failed", it)
                    com.opentune.content.contract.EndpointValidationResult.Error(it.message ?: "validation failed")
                }
                when (result) {
                    is com.opentune.content.contract.EndpointValidationResult.Error -> {
                        call.respondText(
                            json.encodeToString(AddServerResponse(error = result.message)),
                            ContentType.Application.Json,
                            HttpStatusCode.UnprocessableEntity,
                        )
                    }
                    is com.opentune.content.contract.EndpointValidationResult.Success -> {
                        val identityKeys = provider.getFieldsSpec()
                            .filter { it.identity }
                            .map { it.id }
                            .toSet()
                        val hash = computeEndpointHash(result.fields, identityKeys)
                        val endpointId = "${body.protocol}_${hash}"
                        val displayName = result.fields["name"] ?: body.fields["url"] ?: body.fields["host"] ?: body.fields["base_url"] ?: body.protocol
                        val now = System.currentTimeMillis()
                        val entity = EndpointEntity(
                            endpointId = endpointId,
                            protocol = body.protocol,
                            displayName = displayName,
                            fieldsJson = Json.encodeToString(result.fields),
                            createdAtEpochMs = now,
                            updatedAtEpochMs = now,
                        )
                        runCatching { ctx.endpointDao.insert(entity) }.onFailure {
                            Log.w(LOG_TAG, "insert failed (may already exist): ${it.message}")
                        }
                        ctx.registerClient(endpointId, entity)
                        Log.i(LOG_TAG, "added server $endpointId ($displayName)")
                        call.respondText(
                            json.encodeToString(AddServerResponse(endpointId = endpointId, displayName = displayName)),
                            ContentType.Application.Json,
                            HttpStatusCode.Created,
                        )
                    }
                }
            }
            delete("/{endpointId}") {
                val endpointId = call.parameters["endpointId"] ?: return@delete call.respond400("missing endpointId")
                ctx.endpointDao.deleteByEndpointId(endpointId)
                call.respondText("{}", ContentType.Application.Json)
            }
        }

        route("/clients") {
            get {
                val servers = ctx.endpointDao.observeAll().first()
                val dtos = servers.map { s -> ServerDto(s.endpointId, s.protocol, s.displayName) }
                call.respondText(json.encodeToString(dtos), ContentType.Application.Json)
            }

            get("/{endpointId}/browse") {
                val endpointId = call.parameters["endpointId"] ?: return@get call.respond400("missing endpointId")
                val location = call.request.queryParameters["location"]
                val start = call.request.queryParameters["start"]?.toIntOrNull() ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val instance = ctx.getClient(endpointId) ?: return@get call.respond404("unknown endpointId")
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

            get("/{endpointId}/detail") {
                val endpointId = call.parameters["endpointId"] ?: return@get call.respond400("missing endpointId")
                val ref = call.request.queryParameters["ref"] ?: return@get call.respond400("missing ref")
                val instance = ctx.getClient(endpointId) ?: return@get call.respond404("unknown endpointId")
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

            get("/{endpointId}/search") {
                val endpointId = call.parameters["endpointId"] ?: return@get call.respond400("missing endpointId")
                val scope = call.request.queryParameters["scope"] ?: ""
                val query = call.request.queryParameters["q"] ?: return@get call.respond400("missing q")
                val instance = ctx.getClient(endpointId) ?: return@get call.respond404("unknown endpointId")
                val results = runCatching { instance.search(scope, SearchQuery(term = query)).items }.getOrElse {
                    Log.e(LOG_TAG, "search error", it); return@get call.respond500(it.message)
                }
                val dtos = results.map { e ->
                    EntryInfoDto(ref = e.id, title = e.title, type = e.type.name, cover = e.cover)
                }
                call.respondText(json.encodeToString(dtos), ContentType.Application.Json)
            }

            get("/{endpointId}/playback") {
                val endpointId = call.parameters["endpointId"] ?: return@get call.respond400("missing endpointId")
                val ref = call.request.queryParameters["ref"] ?: return@get call.respond400("missing ref")
                val startMs = call.request.queryParameters["startMs"]?.toLongOrNull() ?: 0L
                val instance = ctx.getClient(endpointId) ?: return@get call.respond404("unknown endpointId")
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

            get("/{endpointId}/qr") {
                val endpointId = call.parameters["endpointId"] ?: return@get call.respond400("missing endpointId")
                val instance = ctx.getClient(endpointId) ?: return@get call.respond404("unknown endpointId")
                val ready = runCatching { instance.getQr() }.getOrElse {
                    Log.e(LOG_TAG, "getQr error", it); return@get call.respond500(it.message)
                } ?: return@get call.respond404("provider does not support QR")
                val poll = runCatching { instance.pollQr(ready.token) }.getOrElse {
                    Log.e(LOG_TAG, "pollQr error", it); return@get call.respond500(it.message)
                }
                val fields = (poll as? QrResult.Confirmed)?.fields ?: emptyMap()
                call.respondText(
                    json.encodeToString(fields),
                    ContentType.Application.Json,
                )
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
                    val s = body.endpointId ?: return@post call.respond400("missing endpointId")
                    NavCommand.Browse(p, s, body.itemRef)
                }
                "detail" -> {
                    val p = body.provider ?: return@post call.respond400("missing provider")
                    val s = body.endpointId ?: return@post call.respond400("missing endpointId")
                    val r = body.itemRef ?: return@post call.respond400("missing itemRef")
                    NavCommand.Detail(p, s, r)
                }
                "player" -> {
                    val p = body.provider ?: return@post call.respond400("missing provider")
                    val s = body.endpointId ?: return@post call.respond400("missing endpointId")
                    val r = body.itemRef ?: return@post call.respond400("missing itemRef")
                    NavCommand.Player(p, s, r, body.startMs)
                }
                "image" -> {
                    val p = body.provider ?: return@post call.respond400("missing provider")
                    val s = body.endpointId ?: return@post call.respond400("missing endpointId")
                    val r = body.itemRef ?: return@post call.respond400("missing itemRef")
                    NavCommand.Image(p, s, r)
                }
                "search" -> {
                    val p = body.provider ?: return@post call.respond400("missing provider")
                    val s = body.endpointId ?: return@post call.respond400("missing endpointId")
                    NavCommand.Search(p, s, body.itemRef ?: "")
                }
                else -> return@post call.respond400("unknown route: ${body.route}")
            }
            NavigationBridge.commands.trySend(cmd)
            Log.i(LOG_TAG, "navigate command sent: $cmd")
            call.respondText(json.encodeToString(OkResponse()), ContentType.Application.Json)
        }

        // --- Debug routes for media state ---
        route("/debug") {
            // JAR bridge — proxies host.jar.* calls from the Node test harness via adb forward
            post("/jar") {
                val body = runCatching { json.decodeFromString<JarRequest>(call.receiveText()) }.getOrNull()
                if (body == null) {
                    call.respond400("invalid request body"); return@post
                }
                val result = runCatching { ctx.jarBridge.dispatch(body.name, body.args) }.getOrElse {
                    Log.e(LOG_TAG, "jar.${body.name} error", it)
                    call.respondText(
                        json.encodeToString(JarResponse(error = it.message ?: "jar error")),
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError,
                    )
                    return@post
                }
                call.respondText(json.encodeToString(JarResponse(result = result)), ContentType.Application.Json)
            }

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

            route("/media-state") {                get {
                    val protocol = call.request.queryParameters["protocol"] ?: return@get call.respond400("missing protocol")
                    val endpointId = call.request.queryParameters["endpointId"] ?: return@get call.respond400("missing endpointId")
                    val all = ctx.entryStateStore.observeForEndpoint(protocol, endpointId).first()
                    val dtos = all.map { it.toDto() }
                    call.respondText(json.encodeToString(dtos), ContentType.Application.Json)
                }

                get("/{protocol}/{endpointId}/{itemId}") {
                    val protocol = call.parameters["protocol"] ?: return@get call.respond400("missing protocol")
                    val endpointId = call.parameters["endpointId"] ?: return@get call.respond400("missing endpointId")
                    val itemId = call.parameters["itemId"] ?: return@get call.respond400("missing itemId")
                    val snapshot = ctx.entryStateStore.get(protocol, endpointId, itemId)
                    if (snapshot == null) {
                        call.respond404("no state found for $protocol/$endpointId/$itemId")
                        return@get
                    }
                    call.respondText(json.encodeToString(snapshot.toDto()), ContentType.Application.Json)
                }

                post("/subtitle-track") {
                    val body = runCatching { json.decodeFromString<SetTrackRequest>(call.receiveText()) }.getOrNull()
                    if (body == null) {
                        call.respond400("invalid request body"); return@post
                    }
                    ctx.entryStateStore.upsertSubtitleTrack(EntryStateKey(body.protocol, body.endpointId, body.itemId), body.trackId)
                    val snapshot = ctx.entryStateStore.get(body.protocol, body.endpointId, body.itemId)
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
                    ctx.entryStateStore.upsertAudioTrack(EntryStateKey(body.protocol, body.endpointId, body.itemId), body.trackId)
                    val snapshot = ctx.entryStateStore.get(body.protocol, body.endpointId, body.itemId)
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

private fun computeEndpointHash(fields: Map<String, String>, identityKeys: Set<String>): String {
    val input = fields.entries
        .filter { it.key in identityKeys }
        .sortedBy { it.key }
        .joinToString { "${it.key}=${it.value}" }
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { b -> "%02x".format(b) }
}

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
