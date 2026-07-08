package com.insomnia.server.debug

import com.insomnia.server.AppContext
import com.insomnia.content.contract.SearchQuery
import com.insomnia.core.form.contract.QrResult
import com.insomnia.proxy.contract.ProxyProviderRegistryHolder
import com.insomnia.proxy.contract.ProxyValidationResult
import com.insomnia.storage.EndpointEntity
import com.insomnia.storage.EntryStateKey
import com.insomnia.storage.ProxyEntity
import com.insomnia.storage.SubtitlePrefs
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
import timber.log.Timber
import java.util.UUID

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
                    Timber.e(it, "client.test failed")
                    com.insomnia.content.contract.EndpointValidationResult.Error(it.message ?: "validation failed")
                }
                when (result) {
                    is com.insomnia.content.contract.EndpointValidationResult.Error -> {
                        call.respondText(
                            json.encodeToString(AddServerResponse(error = result.message)),
                            ContentType.Application.Json,
                            HttpStatusCode.UnprocessableEntity,
                        )
                    }
                    is com.insomnia.content.contract.EndpointValidationResult.Success -> {
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
                            Timber.w("insert failed (may already exist): ${it.message}")
                        }
                        ctx.registerClient(endpointId, entity)
                        Timber.i("added server $endpointId ($displayName)")
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

            // Attach (or detach) a proxy to an endpoint. Rebuilds the live client.
            // Body: {"proxyId":"<id>"} to attach, {"proxyId":null} to detach.
            post("/{endpointId}/proxy") {
                val endpointId = call.parameters["endpointId"] ?: return@post call.respond400("missing endpointId")
                val body = runCatching { json.decodeFromString<SetEndpointProxyRequest>(call.receiveText()) }.getOrNull()
                if (body == null) {
                    call.respond400("invalid request body"); return@post
                }
                val existing = ctx.endpointDao.getByEndpointId(endpointId)
                    ?: return@post call.respond404("unknown endpointId")
                if (body.proxyId != null) {
                    val proxy = ctx.proxyDao.getById(body.proxyId)
                        ?: return@post call.respond404("unknown proxyId")
                    Timber.i("attaching proxy ${proxy.id} (${proxy.displayName}) to endpoint $endpointId")
                } else {
                    Timber.i("detaching proxy from endpoint $endpointId")
                }
                val now = System.currentTimeMillis()
                val updated = existing.copy(proxyId = body.proxyId, updatedAtEpochMs = now)
                ctx.endpointDao.update(updated)
                ctx.registerClient(endpointId, updated) // rebuilds the live client with the new proxyId
                call.respondText(json.encodeToString(OkResponse()), ContentType.Application.Json)
            }
        }

        route("/proxies") {
            get {
                val proxies = ctx.proxyDao.getAll()
                val dtos = proxies.map { p -> ProxyDto(p.id, p.proxyType, p.displayName) }
                call.respondText(json.encodeToString(dtos), ContentType.Application.Json)
            }

            post {
                val body = runCatching { json.decodeFromString<AddProxyRequest>(call.receiveText()) }.getOrNull()
                if (body == null) {
                    call.respondText(
                        json.encodeToString(AddProxyResponse(error = "invalid request body")),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }
                val provider = runCatching { ProxyProviderRegistryHolder.get().proxy(body.proxyType) }.getOrNull()
                if (provider == null) {
                    call.respondText(
                        json.encodeToString(AddProxyResponse(error = "unknown proxy type: ${body.proxyType}")),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }
                val client = runCatching { provider.createClient(body.fields) }.getOrElse {
                    call.respondText(
                        json.encodeToString(AddProxyResponse(error = it.message ?: "failed to create client")),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                    return@post
                }
                val result = runCatching { client.test() }.getOrElse {
                    Timber.e(it, "proxy test failed")
                    ProxyValidationResult.Error(it.message ?: "validation failed")
                }
                when (result) {
                    is ProxyValidationResult.Error -> {
                        call.respondText(
                            json.encodeToString(AddProxyResponse(error = result.message)),
                            ContentType.Application.Json,
                            HttpStatusCode.UnprocessableEntity,
                        )
                    }
                    is ProxyValidationResult.Success -> {
                        val entity = ProxyEntity(
                            id = UUID.randomUUID().toString(),
                            proxyType = body.proxyType,
                            displayName = result.name,
                            fieldsJson = Json.encodeToString(result.fields),
                            createdAtEpochMs = System.currentTimeMillis(),
                        )
                        runCatching { ctx.proxyDao.insert(entity) }.onFailure {
                            Timber.w("proxy insert failed (may already exist): ${it.message}")
                        }
                        Timber.i("added proxy ${entity.id} (${entity.displayName})")
                        call.respondText(
                            json.encodeToString(AddProxyResponse(proxyId = entity.id, displayName = entity.displayName)),
                            ContentType.Application.Json,
                            HttpStatusCode.Created,
                        )
                    }
                }
            }

            delete("/{proxyId}") {
                val proxyId = call.parameters["proxyId"] ?: return@delete call.respond400("missing proxyId")
                val affected = ctx.endpointDao.getByProxyId(proxyId)
                val now = System.currentTimeMillis()
                affected.forEach { entity ->
                    val updated = entity.copy(proxyId = null, updatedAtEpochMs = now)
                    ctx.endpointDao.update(updated)
                    ctx.registerClient(entity.endpointId, updated)
                }
                ctx.proxyDao.deleteById(proxyId)
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

                Timber.d("[debug browse] location=$location")

                val result = runCatching { instance.listEntry(location, start, limit) }.getOrElse {
                    Timber.e(it, "listEntry error"); return@get call.respond500(it.message)
                }
                val dto = EntryListDto(
                    items = result.items.map { e ->
                        EntryInfoDto(ref = e.ref, title = e.title, type = e.type, cover = e.cover)
                    },
                    totalCount = result.totalCount,
                )
                call.respondText(json.encodeToString(dto), ContentType.Application.Json)
            }

            get("/{endpointId}/search") {
                val endpointId = call.parameters["endpointId"] ?: return@get call.respond400("missing endpointId")
                val scope = call.request.queryParameters["scope"] ?: ""
                val query = call.request.queryParameters["q"] ?: return@get call.respond400("missing q")
                val instance = ctx.getClient(endpointId) ?: return@get call.respond404("unknown endpointId")
                val results = runCatching { instance.search(scope, SearchQuery(term = query)).items }.getOrElse {
                    Timber.e(it, "search error"); return@get call.respond500(it.message)
                }
                val dtos = results.map { e ->
                    EntryInfoDto(ref = e.ref, title = e.title, type = e.type, cover = e.cover)
                }
                call.respondText(json.encodeToString(dtos), ContentType.Application.Json)
            }

            get("/{endpointId}/playback") {
                val endpointId = call.parameters["endpointId"] ?: return@get call.respond400("missing endpointId")
                val ref = call.request.queryParameters["ref"] ?: return@get call.respond400("missing ref")
                val startMs = call.request.queryParameters["startMs"]?.toLongOrNull() ?: 0L
                val instance = ctx.getClient(endpointId) ?: return@get call.respond404("unknown endpointId")
                val sources = runCatching { instance.getPlaybackSources(ref, startMs) }.getOrElse {
                    Timber.e(it, "getPlaybackSources error"); return@get call.respond500(it.message)
                }
                val source = sources.firstOrNull() ?: return@get call.respond500("no sources")
                val dto = PlaybackSpecDto(
                    url = source.url,
                    mimeType = source.mimeType,
                    headers = source.headers,
                    mediaCodecs = source.mediaCodecs.map { MediaCodecInfoDto(codec = it.codec, bitDepth = it.bitDepth, bitrate = it.bitrate) },
                )
                call.respondText(json.encodeToString(dto), ContentType.Application.Json)
            }

            get("/{endpointId}/qr") {
                val endpointId = call.parameters["endpointId"] ?: return@get call.respond400("missing endpointId")
                val instance = ctx.getClient(endpointId) ?: return@get call.respond404("unknown endpointId")
                val ready = runCatching { instance.getQr() }.getOrElse {
                    Timber.e(it, "getQr error"); return@get call.respond500(it.message)
                } ?: return@get call.respond404("provider does not support QR")
                val poll = runCatching { instance.pollQr(ready.token) }.getOrElse {
                    Timber.e(it, "pollQr error"); return@get call.respond500(it.message)
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
            val cmd: AppCommand = when (body.route) {
                "home" -> AppCommand.Home
                "browse" -> {
                    val endpointId = body.endpointId ?: return@post call.respond400("missing endpointId")
                    val ref = body.itemRef ?: return@post call.respond400("missing itemRef")
                    AppCommand.Browse(endpointId, ref)
                }
                "detail" -> {
                    val endpointId = body.endpointId ?: return@post call.respond400("missing endpointId")
                    val ref = body.itemRef ?: return@post call.respond400("missing itemRef")
                    AppCommand.Detail(endpointId, ref)
                }
                "player" -> {
                    val endpointId = body.endpointId ?: return@post call.respond400("missing endpointId")
                    val ref = body.itemRef ?: return@post call.respond400("missing itemRef")
                    AppCommand.Player(endpointId, ref, body.startMs)
                }
                "image" -> {
                    val endpointId = body.endpointId ?: return@post call.respond400("missing endpointId")
                    val ref = body.itemRef ?: return@post call.respond400("missing itemRef")
                    AppCommand.Image(endpointId, ref)
                }
                "search" -> {
                    val endpointId = body.endpointId ?: return@post call.respond400("missing endpointId")
                    AppCommand.Search(endpointId, body.itemRef ?: "")
                }
                else -> return@post call.respond400("unknown route: ${body.route}")
            }
            AppCommandBridge.commands.trySend(cmd)
            Timber.i("navigate command sent: $cmd")
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
                    Timber.e(it, "jar.${body.name} error")
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

            post("/seek") {
                val body = runCatching { json.decodeFromString<SeekRequest>(call.receiveText()) }.getOrNull()
                if (body == null) {
                    call.respond400("invalid request body"); return@post
                }
                if (body.positionMs == null && body.deltaMs == null) {
                    call.respond400("provide positionMs or deltaMs"); return@post
                }
                AppCommandBridge.commands.trySend(AppCommand.Seek(body.positionMs, body.deltaMs))
                call.respondText(json.encodeToString(OkResponse()), ContentType.Application.Json)
            }

            route("/media-state") {                get {
                    val endpointId = call.request.queryParameters["endpointId"] ?: return@get call.respond400("missing endpointId")
                    val all = ctx.entryStateStore.observeForEndpoint(endpointId).first()
                    val dtos = all.map { it.toDto() }
                    call.respondText(json.encodeToString(dtos), ContentType.Application.Json)
                }

                get("/{endpointId}/{itemRef}") {
                    val endpointId = call.parameters["endpointId"] ?: return@get call.respond400("missing endpointId")
                    val itemRef = call.parameters["itemRef"] ?: return@get call.respond400("missing itemRef")
                    val snapshot = ctx.entryStateStore.get(endpointId, itemRef)
                    if (snapshot == null) {
                        call.respond404("no state found for $endpointId/$itemRef")
                        return@get
                    }
                    call.respondText(json.encodeToString(snapshot.toDto()), ContentType.Application.Json)
                }

                post("/subtitle-track") {
                    val body = runCatching { json.decodeFromString<SetTrackRequest>(call.receiveText()) }.getOrNull()
                    if (body == null) {
                        call.respond400("invalid request body"); return@post
                    }
                    ctx.entryStateStore.upsertSubtitleTrack(EntryStateKey(body.endpointId, body.itemRef), body.trackId)
                    val snapshot = ctx.entryStateStore.get(body.endpointId, body.itemRef)
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
                    ctx.entryStateStore.upsertAudioTrack(EntryStateKey(body.endpointId, body.itemRef), body.trackId)
                    val snapshot = ctx.entryStateStore.get(body.endpointId, body.itemRef)
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
