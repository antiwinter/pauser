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
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Media3 [androidx.media3.datasource.DataSource] that reads a file from an open SMB [DiskShare].
 * The share must stay connected for the lifetime of playback.
 *
 * Uses offset-based SMB reads (not [java.io.InputStream.skip]) so ExoPlayer seeks and sequential
 * reads stay aligned with the file on the server.
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
            val toRead = minOf(length.toLong(), bytesRemaining).toInt()
            if (toRead == 0) return C.RESULT_END_OF_INPUT
            readCallCount++
            val r = try {
                file.read(buffer, readPosition, offset, toRead)
            } catch (e: Exception) {
                Log.e(TAG, "[$sourceId] read SMB error pos=$readPosition toRead=$toRead", e)
                throw IOException(e.message, e)
            }
            if (r == -1) {
                Log.d(TAG, "[$sourceId] read EOF from server pos=$readPosition")
                return C.RESULT_END_OF_INPUT
            }
            if (r > 0) {
                readPosition += r.toLong()
                bytesRemaining -= r.toLong()
                totalBytesRead += r.toLong()
                bytesTransferred(r)
            }
            if (readCallCount <= 5 || readCallCount % 200 == 0) {
                Log.d(TAG, "[$sourceId] read#$readCallCount got=$r pos=$readPosition remaining=$bytesRemaining total=$totalBytesRead")
            }
            return r
        }
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
            if (hadTransfer) {
                transferEnded()
            }
        }
    }

    companion object {
        private const val TAG = "OpenTuneSMB"
        private val nextSourceId = AtomicInteger(1)
    }
}
