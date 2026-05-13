package com.opentune.app.ui.catalog

import android.annotation.TargetApi
import android.media.MediaDataSource
import android.os.Build
import com.opentune.provider.ItemStream
import kotlinx.coroutines.runBlocking

/**
 * Bridges an [ItemStream] into Android's [MediaDataSource] so that
 * [android.media.MediaMetadataRetriever] can read from it for embedded picture extraction.
 * Closing is a no-op here: the [ItemStream] lifecycle is owned by [com.opentune.provider.OpenTuneProviderInstance.withStream].
 */
@TargetApi(Build.VERSION_CODES.M)
internal class ItemStreamMediaDataSource(private val stream: ItemStream) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        runBlocking { stream.readAt(position, buffer, offset, size) }.let { if (it == 0) -1 else it }

    override fun getSize(): Long = runBlocking { stream.getSize() }

    override fun close() {
        // Ownership is with withStream; nothing to do here.
    }
}
