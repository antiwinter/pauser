package com.opentune.player.engine

import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.ConcurrentLinkedQueue

internal object BandwidthTracker {

    private data class Entry(val bytes: Long, val second: Long)
    private val entries = ConcurrentLinkedQueue<Entry>()

    @Volatile private var pendingBytes = 0L
    @Volatile private var lastSecond = 0L

    val interceptor: Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        response.newBuilder()
            .body(response.body?.let { body -> CountingResponseBody(body) })
            .build()
    }

    val mbps: Float
        get() {
            val cutoff = (System.currentTimeMillis() / 1000) - 5
            var total = 0L
            var count = 0
            for (e in entries) {
                if (e.second >= cutoff) {
                    total += e.bytes
                    count++
                }
            }
            if (count == 0) return 0f
            return (total.toFloat() / count * 8) / 1_000_000f
        }

    private class CountingResponseBody(private val delegate: ResponseBody) : ResponseBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()

        override fun source(): BufferedSource {
            return delegate.source().let { src ->
                object : ForwardingSource(src) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        val read = super.read(sink, byteCount)
                        if (read > 0) {
                            val now = System.currentTimeMillis() / 1000
                            if (now > lastSecond) {
                                entries.add(Entry(pendingBytes, lastSecond))
                                pendingBytes = 0L
                                lastSecond = now
                                // Prune stale entries to bound memory
                                val cutoff = now - 5
                                val it = entries.iterator()
                                while (it.hasNext()) {
                                    if (it.next().second < cutoff) it.remove()
                                    else break
                                }
                            }
                            pendingBytes += read
                        }
                        return read
                    }
                }.buffer()
            }
        }
    }
}
