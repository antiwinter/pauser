package com.opentune.smb

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File as SmbFile
import com.opentune.provider.EntryDetail
import com.opentune.provider.EntryInfo
import com.opentune.provider.EntryList
import com.opentune.provider.EntryType
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.ProviderStream
import com.opentune.provider.StreamRegistrarHolder
import com.opentune.provider.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet

private const val SMB_LOG = "OpenTunePlayer"

class SmbProviderInstance(
    private val fields: SmbServerFieldsJson,
) : OpenTuneProviderInstance {

    private fun credentials() = SmbCredentials(
        host = fields.host,
        shareName = fields.shareName,
        username = fields.username,
        password = fields.password,
        domain = fields.domain,
    )

    override suspend fun listEntry(location: String?, startIndex: Int, limit: Int): EntryList {
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            try {
                val share = session.share
                val all = share.listDirectory(location ?: "")
                val slice = all.drop(startIndex).take(limit)
                EntryList(items = slice.map { mapEntry(it) }, totalCount = all.size)
            } finally {
                session.close()
            }
        }
    }

    override suspend fun search(scopeLocation: String, query: String): List<EntryInfo> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            try {
                val share = session.share
                share.listDirectory(scopeLocation)
                    .filterByName(query)
                    .map { mapEntry(it) }
            } finally {
                session.close()
            }
        }
    }

    override suspend fun getDetail(itemRef: String): EntryDetail {
        val path = itemRef.replace('\\', '/')
        val name = path.substringAfterLast('/').ifEmpty { path }
        val video = isLikelyVideoFile(name)
        return EntryDetail(
            title = name,
            overview = path,
            logo = null,
            backdrop = emptyList(),
            isMedia = video,
            rating = null,
            bitrate = null,
            externalUrls = emptyList(),
            year = null,
            providerIds = emptyMap(),
            streams = emptyList(),
            etag = null,
        )
    }

    override suspend fun getPlaybackSpec(itemRef: String, startMs: Long): PlaybackSpec {
        return withContext(Dispatchers.IO) {
            val pathWin = itemRef.replace('/', '\\')
            val registrar = StreamRegistrarHolder.get()
            val videoUrl = registrar.registerStream(this@SmbProviderInstance, pathWin)
            Log.d(SMB_LOG, "[smb] registered video stream url=$videoUrl")

            // Scan for sidecar subtitles using a short-lived session.
            val subtitleTracks = runCatching {
                val session = SmbSession.open(credentials())
                try {
                    val rawSubtitles = findSidecarSubtitles(session.share, itemRef)
                    rawSubtitles.mapNotNull { track ->
                        val smbPath = track.externalRef?.replace('/', '\\') ?: return@mapNotNull null
                        val url = registrar.registerStream(this@SmbProviderInstance, smbPath)
                        Log.d(SMB_LOG, "[smb] registered subtitle stream url=$url")
                        track.copy(externalRef = url)
                    }
                } finally {
                    session.close()
                }
            }.getOrElse { e ->
                Log.w(SMB_LOG, "[smb] subtitle scan failed", e)
                emptyList()
            }

            val allTokenUrls = listOf(videoUrl) + subtitleTracks.mapNotNull { it.externalRef }

            PlaybackSpec(
                url = videoUrl,
                headers = emptyMap(),
                mimeType = null,
                title = pathWin.substringAfterLast('\\').ifEmpty { pathWin },
                durationMs = null,
                hooks = SmbPlaybackHooks(allTokenUrls),
                subtitleTracks = subtitleTracks,
            )
        }
    }

    /**
     * Opens a random-access stream for [itemRef].
     * Called by [OpenTuneServer] for each incoming HTTP range request — each call opens
     * its own SMB session and file handle.
     */
    override suspend fun openStream(itemRef: String): ProviderStream {
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            val smbFile = session.share.openFile(
                itemRef,
                EnumSet.of(AccessMask.GENERIC_READ), null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN, null,
            )
            SmbProviderStream(smbFile, session)
        }
    }

    private class SmbProviderStream(
        private val file: SmbFile,
        private val session: SmbSession,
    ) : ProviderStream {
        override suspend fun getSize(): Long =
            file.getFileInformation(FileStandardInformation::class.java).endOfFile

        // Seek-aware read-ahead buffer: one large SMB READ fills the chunk;
        // subsequent calls within the window are served from memory.
        private val chunkBuf = ByteArray(SMB_READ_CHUNK_BYTES)
        private var chunkStart = -1L
        private var chunkLen = 0

        override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            // Refill chunk if the requested position falls outside the current window.
            if (chunkLen == 0 || position < chunkStart || position >= chunkStart + chunkLen) {
                val r = file.read(chunkBuf, position, 0, chunkBuf.size)
                if (r <= 0) return 0
                chunkStart = position
                chunkLen = r
            }
            val indexInChunk = (position - chunkStart).toInt()
            val available = chunkLen - indexInChunk
            val n = minOf(size, available)
            System.arraycopy(chunkBuf, indexInChunk, buffer, offset, n)
            return n
        }

        override fun close() {
            runCatching { file.close() }
            session.close()
        }

        companion object {
            /** One SMB READ per chunk; balances latency vs memory (server may cap ~1 MiB). */
            private const val SMB_READ_CHUNK_BYTES = 128 * 1024
        }
    }

    private fun mapEntry(e: SmbListEntry): EntryInfo {
        val kind = when {
            e.isDirectory -> EntryType.Folder
            isLikelyVideoFile(e.name) -> EntryType.Playable
            else -> EntryType.Other
        }
        return EntryInfo(
            id = e.path,
            title = e.name + if (e.isDirectory) "/" else "",
            type = kind,
            cover = null,
        )
    }

    private fun findSidecarSubtitles(
        share: com.hierynomus.smbj.share.DiskShare,
        itemRef: String,
    ): List<SubtitleTrack> {
        val subtitleExts = setOf(".srt", ".ass", ".ssa", ".vtt", ".sub")
        val parentFolder = itemRef.substringBeforeLast('/', "")
        return share.listDirectory(parentFolder)
            .filter { !it.isDirectory && subtitleExts.any { ext -> it.name.lowercase().endsWith(ext) } }
            .map { entry ->
                SubtitleTrack(
                    trackId = entry.path,
                    label = entry.name,
                    language = null,
                    isDefault = false,
                    isForced = false,
                    externalRef = entry.path,
                )
            }
    }
}
