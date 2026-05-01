package com.opentune.smb

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
import java.io.InputStream
import java.util.EnumSet

/**
 * Media3 [androidx.media3.datasource.DataSource] that reads a file from an open SMB [DiskShare].
 * The share must stay connected for the lifetime of playback.
 */
@UnstableApi
class SmbDataSource(
    private val share: DiskShare,
    private val pathWindowsStyle: String,
) : BaseDataSource(/* isNetwork = */ true) {

    private var smbFile: SmbFile? = null
    private var input: InputStream? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        close()
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
        val stream = file.getInputStream()
        val skip = dataSpec.position
        var skipped = 0L
        while (skipped < skip) {
            val n = stream.skip(skip - skipped)
            if (n <= 0) break
            skipped += n
        }
        input = stream
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            (size - skipped).coerceAtLeast(0L)
        } else {
            dataSpec.length
        }
        opened = true
        transferStarted(dataSpec)
        return size
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining > 0) minOf(length.toLong(), bytesRemaining).toInt() else length
        val stream = input ?: return C.RESULT_END_OF_INPUT
        val read = stream.read(buffer, offset, toRead)
        if (read > 0) {
            if (bytesRemaining > 0) bytesRemaining -= read
        }
        return read
    }

    override fun getUri() = null

    override fun close() {
        if (!opened) return
        opened = false
        runCatching { input?.close() }
        input = null
        runCatching { smbFile?.close() }
        smbFile = null
        bytesRemaining = 0
    }
}
