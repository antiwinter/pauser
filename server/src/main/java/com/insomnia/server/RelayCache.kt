package com.insomnia.server

import com.insomnia.content.contract.ByteSink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
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

    private const val MAX_SPAN = 1024 * 1024

    private val maxBytes = minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)

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

    /** Returns the end (inclusive) of the cached contiguous data starting at [start] up to [maxEnd], or null if [start] is not cached. */
    suspend fun getCachedContiguousEnd(start: Long, maxEnd: Long): Long? = mutex.withLock {
        pos = start
        if (currentKey == null) return@withLock null
        var cur = start
        while (cur <= maxEnd) {
            val span = spans.floorEntry(cur)?.value ?: break
            val spanEnd = span.start + span.data.size
            if (spanEnd <= cur) break
            cur = spanEnd
        }
        return@withLock if (cur > start) (cur - 1).coerceAtMost(maxEnd) else null
    }

    /** Returns the start position of the first cached span strictly after [pos], or null if none exists. */
    suspend fun getNextCachedStart(pos: Long): Long? = mutex.withLock {
        if (currentKey == null) return@withLock null
        return@withLock spans.higherKey(pos)
    }

    /** Serve [start..end] (inclusive) into [sink]. */
    suspend fun tryServe(start: Long, end: Long, sink: ByteSink): Boolean {
        // Snapshot the spans needed under the lock, then serve without holding it.
        // Holding the mutex across slow socket writes blocks `put` and unbounded chunks
        // pile up waiting → OOM.
        data class Slice(val data: ByteArray)
        val slices = mutex.withLock {
            pos = start
            val out = ArrayList<Slice>()
            var cur = start
            while (cur <= end) {
                val span = spans.floorEntry(cur)?.value ?: return@withLock null
                val spanEnd = span.start + span.data.size
                if (spanEnd <= cur) return@withLock null
                val off = (cur - span.start).toInt()
                val len = (spanEnd - cur).toInt().coerceAtMost((end - cur + 1).toInt())
                out.add(Slice(span.data.copyOfRange(off, off + len)))
                cur = span.start + off + len
            }
            out
        } ?: return false
        for (s in slices) sink.write(s.data, 0, s.data.size)
        return true
    }

    /** Append a chunk at [posArg]; coalesce into the previous span when adjacent and under [MAX_SPAN]. */
    suspend fun put(posArg: Long, buf: ByteArray, off: Int, len: Int) = mutex.withLock {
        if (currentKey == null || len <= 0) return@withLock
        pos = posArg
        var cur = posArg
        var bOff = off
        var bLen = len
        while (bLen > 0) {
            val chunkLen = minOf(bLen, MAX_SPAN)
            val prev = spans.lowerEntry(cur)?.value
            if (prev != null && prev.start + prev.data.size == cur && prev.data.size + chunkLen <= MAX_SPAN) {
                val merged = ByteArray(prev.data.size + chunkLen)
                System.arraycopy(prev.data, 0, merged, 0, prev.data.size)
                System.arraycopy(buf, bOff, merged, prev.data.size, chunkLen)
                spans.remove(prev.start)
                totalBytes.addAndGet(-(prev.data.size.toLong()))
                spans[cur]?.let { old -> totalBytes.addAndGet(-(old.data.size.toLong())); spans.remove(cur) }
                spans[prev.start] = Span(prev.start, merged)
                totalBytes.addAndGet(merged.size.toLong())
            } else {
                val data = ByteArray(chunkLen)
                System.arraycopy(buf, bOff, data, 0, chunkLen)
                spans[cur]?.let { old -> totalBytes.addAndGet(-(old.data.size.toLong())) }
                spans[cur] = Span(cur, data)
                totalBytes.addAndGet(chunkLen.toLong())
            }
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
            val victimEnd = victim.start + victim.data.size
            spans.remove(victim.start)
            totalBytes.addAndGet(-(victim.data.size.toLong()))
            // Timber.d("sr: evict [${victim.start}-$victimEnd] size=${victim.data.size}")
        }
    }

    private fun farthestSpan(): Span? {
        val last = spans.lastEntry()?.value ?: return null
        val first = spans.firstEntry()?.value ?: return null
        if (last === first) return null
        val lastEnd = last.start + last.data.size
        val firstEnd = first.start + first.data.size
        val lastDist = when { last.start >= pos -> last.start - pos; lastEnd <= pos -> pos - lastEnd; else -> 0L }
        val firstDist = when { firstEnd <= pos -> pos - firstEnd; first.start >= pos -> first.start - pos; else -> 0L }
        return when {
            lastDist == 0L && firstDist == 0L -> null
            lastDist >= firstDist -> last
            else -> first
        }
    }
}
