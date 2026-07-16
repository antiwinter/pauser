package com.insomnia.content.contract

import com.insomnia.content.epcache.CachingEndpointClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val RELAY_TTL_MS = 30_000L
private const val RELAY_SWEEP_MS = 10_000L
private const val PUMP_CHUNK_SIZE = 128 * 1024

/**
 * One open [ProviderStream] per `(endpointId, itemRef)` so SMB session setup happens once per
 * playback. [withStream] runs under the handle mutex, so an in-flight pump can't be closed
 * mid-stream by [evict] or the sweeper, and smbj `File.read` calls are serialized (not
 * concurrency-safe on their own). Idle handles are reaped after [RELAY_TTL_MS].
 */
object FileRelay {

    private val handles = ConcurrentHashMap<StreamKey, Handle>()
    private val createMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { scope.launch { sweepLoop() } }

    suspend fun ensureOpen(
        endpointId: String,
        itemRef: String,
        open: suspend () -> ProviderStream?,
    ): Boolean = getOrOpen(endpointId, itemRef, open) != null

    /** Open (or reuse) and resolve size, caching `getSize()`. Used to build range headers before pumping. */
    suspend fun ensureSize(
        endpointId: String,
        itemRef: String,
        open: suspend () -> ProviderStream?,
    ): Long? {
        val handle = getOrOpen(endpointId, itemRef, open) ?: return null
        handle.mutex.withLock {
            handle.touch()
            if (handle.cachedSize < 0) {
                handle.cachedSize = withContext(Dispatchers.IO) { handle.stream.getSize() }
            }
            return handle.cachedSize
        }
    }

    suspend fun withStream(
        endpointId: String,
        itemRef: String,
        open: suspend () -> ProviderStream?,
        block: suspend (stream: ProviderStream, size: Long) -> Unit,
    ): Boolean {
        val handle = getOrOpen(endpointId, itemRef, open) ?: return false
        handle.mutex.withLock {
            handle.touch()
            try {
                val size = if (handle.cachedSize < 0) {
                    withContext(Dispatchers.IO) { handle.stream.getSize() }.also { handle.cachedSize = it }
                } else {
                    handle.cachedSize
                }
                block(handle.stream, size)
            } finally {
                handle.touch()
            }
        }
        return true
    }

    fun touch(endpointId: String, itemRef: String) {
        handles[StreamKey(endpointId, itemRef)]?.touch()
    }

    /** Close under the mutex so an in-flight pump finishes first; launched non-blocking since evict is called from the heartbeat path. */
    fun evict(endpointId: String, itemRef: String) {
        val h = handles.remove(StreamKey(endpointId, itemRef)) ?: return
        scope.launch { h.mutex.withLock { runCatching { h.stream.close() } } }
    }

    private suspend fun getOrOpen(
        endpointId: String,
        itemRef: String,
        open: suspend () -> ProviderStream?,
    ): Handle? {
        val key = StreamKey(endpointId, itemRef)
        handles[key]?.let { it.touch(); return it }
        return createMutex.withLock {
            handles[key]?.let { it.touch(); it } ?: run {
                val stream = open() ?: return@withLock null
                Handle(stream).also { handles[key] = it }
            }
        }
    }

    private suspend fun sweepLoop() {
        while (scope.isActive) {
            delay(RELAY_SWEEP_MS)
            val cutoff = System.currentTimeMillis() - RELAY_TTL_MS
            for ((key, h) in handles.toList()) {
                if (h.lastTouched >= cutoff) continue
                // tryLock: skip busy handles (mid-pump) and leave them for the next sweep.
                if (h.mutex.tryLock()) {
                    try {
                        if (handles.remove(key, h)) runCatching { h.stream.close() }
                    } finally {
                        h.mutex.unlock()
                    }
                }
            }
        }
    }
}

private data class StreamKey(val endpointId: String, val itemRef: String)

private class Handle(
    val stream: ProviderStream,
    @Volatile var cachedSize: Long = -1L,
    @Volatile var lastTouched: Long = System.currentTimeMillis(),
    val mutex: Mutex = Mutex(),
) {
    fun touch() { lastTouched = System.currentTimeMillis() }
}

/**
 * The single recipe behind `/relay/fs`. `endpointId`/`itemRef` travel as `ep`/`ref` params, so
 * the URL is deterministic and [com.insomnia.player.engine.PlayerCache] keys stay stable. The
 * pump runs under [FileRelay.withStream] and calls `readAt` directly — no InputStream bridge.
 */
class FileRelayRecipe(
    private val resolveClient: suspend (endpointId: String) -> CachingEndpointClient?,
) : StreamRelayRecipe {
    override suspend fun serve(params: Map<String, String>): StreamRelayResult? {
        val ep = params["ep"] ?: return null
        val ref = params["ref"] ?: return null
        val rangeHeader = params["Range"]
        val totalSize = FileRelay.ensureSize(ep, ref) { resolveClient(ep)?.openStream(ref) }
            ?: return null
        val (start, end) = if (rangeHeader != null) parseRange(rangeHeader, totalSize)
            else 0L to (totalSize - 1)
        val length = (end - start + 1).coerceAtLeast(0L)
        return StreamRelayResult(
            status = if (rangeHeader != null) 206 else 200,
            contentType = null,
            headers = buildMap {
                put("Accept-Ranges", "bytes")
                if (rangeHeader != null) put("Content-Range", "bytes $start-$end/$totalSize")
            },
            length = length,
            pump = { sink ->
                FileRelay.withStream(ep, ref, { resolveClient(ep)?.openStream(ref) }) { stream, _ ->
                    pumpProviderStream(stream, start, length, sink)
                }
            },
        )
    }

    companion object {
        /** Register the `fs` recipe once, lazily; epcache calls this before building any file-service URL. */
        fun ensureRegistered() {
            StreamRelayRegistry.register("fs", FileRelayRecipe { ep ->
                runCatching { EndpointClientRegistryHolder.get().getOrCreate(ep) }.getOrNull()
            })
        }
    }
}

/** 128 KiB chunk — the proven SMB pump size; changing it regressed LAN throughput before. */
private suspend fun pumpProviderStream(
    stream: ProviderStream,
    offset: Long,
    length: Long,
    sink: ByteSink,
) {
    val buf = ByteArray(PUMP_CHUNK_SIZE)
    var remaining = length
    var pos = offset
    while (remaining > 0) {
        val toRead = minOf(remaining, buf.size.toLong()).toInt()
        val read = withContext(Dispatchers.IO) { stream.readAt(pos, buf, 0, toRead) }
        if (read <= 0) break
        sink.write(buf, 0, read)
        pos += read
        remaining -= read
    }
}

fun parseRange(header: String, totalSize: Long): Pair<Long, Long> {
    val spec = header.removePrefix("bytes=")
    val dashIdx = spec.indexOf('-')
    val start = if (dashIdx > 0) spec.substring(0, dashIdx).toLongOrNull() ?: 0L else 0L
    val end = if (dashIdx < spec.lastIndex) {
        spec.substring(dashIdx + 1).toLongOrNull()?.coerceAtMost(totalSize - 1) ?: (totalSize - 1)
    } else {
        totalSize - 1
    }
    return start to end
}
