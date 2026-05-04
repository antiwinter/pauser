package com.opentune.smb

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.IOException
import java.io.InterruptedIOException
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Media3 [androidx.media3.datasource.DataSource] that reads a file from an open SMB [DiskShare].
 * The share must stay connected for the lifetime of playback.
 *
 * Uses offset-based SMB reads (not [java.io.InputStream.skip]) so ExoPlayer seeks and sequential
 * reads stay aligned with the file on the server.
 *
 * **Read-ahead (same idea as a cache of the next bytes):** The MKV extractor often calls
 * [read] with a length of 1. SMBJ’s [com.hierynomus.smbj.share.File.read] issues **one SMB2 READ
 * per call** using that length, so 1-byte calls mean one network round-trip each. This class
 * keeps a fixed-size window and refills it with a single large READ, then serves small requests
 * from memory—like [java.io.BufferedInputStream], but **seek-aware** for [DataSpec.position].
 *
 * **Why not only SMBJ’s buffer:** [com.hierynomus.smbj.share.File.getInputStream] uses an internal
 * [com.hierynomus.smbj.share.FileInputStream] sized by [com.hierynomus.smbj.share.DiskShare.getReadBufferSize],
 * but that stream is **sequential from offset 0** (async pipelined reads). ExoPlayer opens a
 * range at arbitrary offsets and jumps; that access pattern needs random-offset reads, which
 * stay on [File.read] and do not get SMBJ’s InputStream buffering unless we add a layer like
 * this.
 */
@UnstableApi
class SmbDataSource(
    private val share: DiskShare,
    private val pathWindowsStyle: String,
) : BaseDataSource(/* isNetwork = */ true) {

    private val sourceId: Int = nextSourceId.getAndIncrement()

    private var smbFile: SmbFile? = null
    private var readPosition: Long = 0
    private var bytesRemaining: Long = 0
    private var opened = false
    private var readCallCount: Int = 0
    private var totalBytesRead: Long = 0

    /** File offset of [readChunkBuffer][0] when [chunkLength] &gt; 0; otherwise unused. */
    private var chunkFileStart: Long = -1L

    private var chunkLength: Int = 0
    private val readChunkBuffer = ByteArray(SMB_READ_CHUNK_BYTES)

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long = synchronized(this) {
        close()
        transferInitializing(dataSpec)
        Log.d(
            TAG,
            "[$sourceId] open path=$pathWindowsStyle uri=${dataSpec.uri} pos=${dataSpec.position} len=${dataSpec.length} flags=${dataSpec.flags}",
        )
        val file = share.openFile(
            pathWindowsStyle,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        smbFile = file
        val standard = file.getFileInformation(FileStandardInformation::class.java)
        val size = standard.endOfFile
        readPosition = dataSpec.position
        if (readPosition > size) {
            throw IOException("Read position $readPosition beyond file size $size")
        }
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            size - readPosition
        } else {
            dataSpec.length
        }
        if (bytesRemaining < 0) {
            throw IOException("Invalid length for SMB read")
        }
        opened = true
        transferStarted(dataSpec)
        readCallCount = 0
        totalBytesRead = 0
        clearChunkBuffer()
        Log.d(TAG, "[$sourceId] open ok fileSize=$size readFrom=$readPosition returningBytes=$bytesRemaining")
        bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        synchronized(this) {
            if (bytesRemaining == 0L) {
                Log.d(TAG, "[$sourceId] read requested len=$length but bytesRemaining=0 -> END_OF_INPUT")
                return C.RESULT_END_OF_INPUT
            }
            val file = smbFile ?: run {
                Log.w(TAG, "[$sourceId] read with smbFile=null -> END_OF_INPUT")
                return C.RESULT_END_OF_INPUT
            }
            val toRead = min(length.toLong(), bytesRemaining).toInt()
            if (toRead == 0) return C.RESULT_END_OF_INPUT

            readCallCount++
            var totalCopied = 0
            var dstOffset = offset
            var left = toRead

            while (left > 0) {
                if (chunkLength == 0 ||
                    readPosition < chunkFileStart ||
                    readPosition >= chunkFileStart + chunkLength
                ) {
                    val loaded = loadChunk(file, readPosition)
                    if (!loaded) {
                        return if (totalCopied > 0) totalCopied else C.RESULT_END_OF_INPUT
                    }
                }
                val indexInChunk = (readPosition - chunkFileStart).toInt()
                val availableInChunk = chunkLength - indexInChunk
                val n = min(availableInChunk, left)
                System.arraycopy(readChunkBuffer, indexInChunk, buffer, dstOffset, n)
                dstOffset += n
                readPosition += n.toLong()
                bytesRemaining -= n.toLong()
                totalBytesRead += n.toLong()
                totalCopied += n
                left -= n
            }

            if (totalCopied > 0) {
                bytesTransferred(totalCopied)
            }
            if (readCallCount <= 5 || readCallCount % 5000 == 0) {
                Log.d(
                    TAG,
                    "[$sourceId] read#$readCallCount copied=$totalCopied pos=$readPosition remaining=$bytesRemaining total=$totalBytesRead chunk=$chunkLength",
                )
            }
            return totalCopied
        }
    }

    /**
     * Fills [readChunkBuffer] for the byte at [fileOffset]. Returns false if EOF / empty read.
     */
    @Throws(IOException::class)
    private fun loadChunk(file: SmbFile, fileOffset: Long): Boolean {
        clearChunkBuffer()
        val maxFromServer = min(readChunkBuffer.size.toLong(), bytesRemaining).toInt()
        if (maxFromServer <= 0) return false

        val r = try {
            file.read(readChunkBuffer, fileOffset, 0, maxFromServer)
        } catch (e: Exception) {
            if (e.isInterruptedOrCausedByInterrupted() || Thread.interrupted()) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("SMB read interrupted").initCause(e)
            }
            Log.e(TAG, "[$sourceId] read SMB error pos=$fileOffset toRead=$maxFromServer", e)
            throw IOException(e.message, e)
        }
        if (r == -1) {
            Log.d(TAG, "[$sourceId] read EOF from server pos=$fileOffset")
            return false
        }
        if (r == 0) {
            return false
        }
        chunkFileStart = fileOffset
        chunkLength = r
        return true
    }

    private fun clearChunkBuffer() {
        chunkFileStart = -1L
        chunkLength = 0
    }

    override fun getUri() = null

    override fun close() {
        synchronized(this) {
            val hadTransfer = opened
            if (hadTransfer || smbFile != null) {
                Log.d(TAG, "[$sourceId] close hadTransfer=$hadTransfer readCalls=$readCallCount totalBytes=$totalBytesRead")
            }
            opened = false
            runCatching { smbFile?.close() }
            smbFile = null
            bytesRemaining = 0
            readPosition = 0
            clearChunkBuffer()
            if (hadTransfer) {
                transferEnded()
            }
        }
    }

    companion object {
        private const val TAG = "OpenTuneSMB"
        private val nextSourceId = AtomicInteger(1)

        /** One SMB READ per chunk; balances latency vs memory (server may cap ~1 MiB). */
        private const val SMB_READ_CHUNK_BYTES = 512 * 1024
    }
}

private fun Throwable.isInterruptedOrCausedByInterrupted(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is InterruptedException) return true
        t = t.cause
    }
    return false
}
