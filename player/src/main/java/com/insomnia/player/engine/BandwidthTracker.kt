package com.insomnia.player.engine

import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

internal object BandwidthTracker {

    private const val WINDOW_MS = 3000L
    private const val BUCKET_MS = 250L

    // Bytes are coalesced into time buckets so a fast download doesn't create one entry per read.
    private class Bucket(val tMs: Long, var bytes: Long)
    private val buckets = ArrayDeque<Bucket>()
    private val totalBytesCounter = AtomicLong(0L)
    private val lock = Any()

    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        val range = request.header("Range") ?: "none"
        // Timber.d("BW request: ${request.method} ${request.url} range=$range")
        val response = chain.proceed(request)
        response.newBuilder()
            .body(response.body?.let { body -> CountingResponseBody(body) })
            .build()
    }

    val totalBytes: Long get() = totalBytesCounter.get()

    fun reset() = synchronized(lock) {
        totalBytesCounter.set(0L)
        buckets.clear()
    }

    private fun record(bytes: Long) {
        totalBytesCounter.addAndGet(bytes)
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val bucketT = now - (now % BUCKET_MS)
            val last = buckets.lastOrNull()
            if (last != null && last.tMs == bucketT) last.bytes += bytes
            else buckets.addLast(Bucket(bucketT, bytes))
            val cutoff = now - WINDOW_MS
            while (buckets.isNotEmpty() && buckets.first().tMs < cutoff) buckets.removeFirst()
        }
    }

    /** Rolling download rate over the last [WINDOW_MS]; 0 when nothing is being downloaded. */
    val mbps: Float
        get() = synchronized(lock) {
            val cutoff = System.currentTimeMillis() - WINDOW_MS
            var total = 0L
            for (b in buckets) if (b.tMs >= cutoff) total += b.bytes
            (total * 8f) / (WINDOW_MS / 1000f) / 1_000_000f
        }

    private class CountingResponseBody(private val delegate: ResponseBody) : ResponseBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()

        override fun source(): BufferedSource {
            return delegate.source().let { src ->
                object : ForwardingSource(src) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        val read = super.read(sink, byteCount)
                        if (read > 0) record(read)
                        return read
                    }
                }.buffer()
            }
        }
    }
}
