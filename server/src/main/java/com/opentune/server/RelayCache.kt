package com.opentune.server

import com.opentune.content.contract.ByteSink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Session-only in-RAM byte cache for the stream relay. One key at a time (the current playback);
 * [beginRequest] with a different key drops all previous spans + metadata wholesale. Within the
 * current key, spans are bounded by [maxBytes]; the span farthest from the play position is
 * evicted first.
 *
 * The relay route is the only client; recipes never touch it. No persistence — dies with the
 * process. One player means only the current video is cached; the next playback's first request
 * reclaims the old cache via [beginRequest], so there is no evict-on-STOPPED hook.
 */
object RelayCache {

    private const val MAX_SPAN = 256 * 1024

    private val maxBytes = minOf(150L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 3)

    private val mutex = Mutex()
    @Volatile private var currentKey: String? = null
    @Volatile private var pos: Long = 0L
    @Volatile private var meta: Meta? = null
    private val spans = TreeMap<Long, Span>()
    private val totalBytes = AtomicLong(0L)

    data class Meta(val totalSize: Long, val contentType: String?)

    private class Span(val start: Long, val data: ByteArray)

    /** Switch to [key]; if it differs from the current key, drop all spans + meta. */
    suspend fun beginRequest(key: String) = mutex.withLock {
        if (currentKey != key) {
            currentKey = key
            spans.clear()
            totalBytes.set(0L)
            meta = null
        }
    }

    /** Full-range hit of [start..end] (inclusive) within the current key? Updates [pos] from [start]. */
    suspend fun covers(start: Long, end: Long): Boolean = mutex.withLock {
        pos = start
        if (currentKey == null) return@withLock false
        var cur = start
        while (cur <= end) {
            val span = spans.floorEntry(cur)?.value ?: return@withLock false
            val spanEnd = span.start + span.data.size
            if (spanEnd <= cur) return@withLock false
            cur = spanEnd
        }
        true
    }

    /** Serve [start..end] (inclusive) into [sink]. Only call after [covers] returned true. */
    suspend fun tryServe(start: Long, end: Long, sink: ByteSink): Boolean = mutex.withLock {
        pos = start
        var cur = start
        while (cur <= end) {
            val span = spans.floorEntry(cur)?.value ?: return@withLock false
            val spanEnd = span.start + span.data.size
            if (spanEnd <= cur) return@withLock false
            val off = (cur - span.start).toInt()
            val len = (spanEnd - cur).toInt().coerceAtMost((end - cur + 1).toInt())
            sink.write(span.data, off, len)
            cur = span.start + off + len
        }
        true
    }

    /** Add a chunk at [posArg]; cap span size at [MAX_SPAN]; evict farthest-from-pos if over [maxBytes]. */
    suspend fun put(posArg: Long, buf: ByteArray, off: Int, len: Int) = mutex.withLock {
        if (currentKey == null || len <= 0) return@withLock
        pos = posArg
        var cur = posArg
        var bOff = off
        var bLen = len
        while (bLen > 0) {
            val chunkLen = minOf(bLen, MAX_SPAN)
            val data = ByteArray(chunkLen)
            System.arraycopy(buf, bOff, data, 0, chunkLen)
            spans[cur]?.let { old -> totalBytes.addAndGet(-(old.data.size.toLong())) }
            spans[cur] = Span(cur, data)
            totalBytes.addAndGet(chunkLen.toLong())
            cur += chunkLen
            bOff += chunkLen
            bLen -= chunkLen
        }
        evictIfOver()
    }

    fun storeMeta(totalSize: Long, contentType: String?) {
        meta = Meta(totalSize, contentType)
    }

    fun meta(): Meta? = meta

    private fun evictIfOver() {
        var guard = 0
        while (totalBytes.get() > maxBytes && guard++ < 256) {
            val victim = farthestSpan() ?: return
            spans.remove(victim.start)
            totalBytes.addAndGet(-(victim.data.size.toLong()))
        }
    }

    private fun farthestSpan(): Span? {
        var best: Span? = null
        var bestDist = -1L
        for (span in spans.values) {
            val d = distance(span)
            if (d == 0L) continue  // never evict the span containing pos
            if (d > bestDist) { bestDist = d; best = span }
        }
        return best
    }

    private fun distance(span: Span): Long {
        val spanStart = span.start
        val spanEnd = span.start + span.data.size  // exclusive
        return when {
            pos >= spanStart && pos < spanEnd -> 0L
            spanStart >= pos -> spanStart - pos
            else -> pos - spanEnd
        }
    }
}
