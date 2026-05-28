package com.opentune.smb

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File as SmbFile
import com.opentune.content.contract.EntryDetail
import com.opentune.content.contract.EntryInfo
import com.opentune.content.contract.EntryList
import com.opentune.content.contract.EntryType
import com.opentune.content.contract.SearchQuery
import com.opentune.content.contract.SortField
import com.opentune.content.contract.SortOrder
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EndpointValidationResult
import com.opentune.content.contract.PlaybackSpec
import com.opentune.content.contract.ProviderStream
import com.opentune.content.contract.StreamRegistrarHolder
import com.opentune.content.contract.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.EnumSet

private const val SMB_LOG = "OpenTunePlayer"

class SmbProviderInstance(
    private val fields: SmbServerFieldsJson,
) : EndpointClient() {

    private fun credentials() = SmbCredentials(
        host = fields.host,
        shareName = fields.shareName,
        username = fields.username,
        password = fields.password,
        domain = fields.domain,
    )

    override suspend fun test(): EndpointValidationResult = withContext(Dispatchers.IO) {
        try {
            val session = SmbSession.open(credentials())
            session.close()
            EndpointValidationResult.Success(
                fields = buildMap {
                    put("host", fields.host)
                    put("share_name", fields.shareName)
                    put("username", fields.username)
                    put("password", fields.password)
                    put("name", fields.shareName)
                    fields.domain?.let { put("domain", it) }
                },
            )
        } catch (e: Exception) {
            EndpointValidationResult.Error(e.message ?: "SMB validation failed")
        }
    }

    override suspend fun listEntry(
        location: String?,
        startIndex: Int,
        limit: Int,
        sortBy: SortField?,
        sortOrder: SortOrder,
    ): EntryList {
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            try {
                val share = session.share
                val all = share.listDirectory(location ?: "")
                val sorted = when (sortBy) {
                    SortField.Title, SortField.IndexNumber, null -> all.sortedBy { it.name }
                    else -> all.sortedBy { it.name }
                }.let { if (sortOrder == SortOrder.Descending) it.reversed() else it }
                val slice = sorted.drop(startIndex).take(limit)
                EntryList(items = slice.map { mapEntry(it) }, totalCount = all.size)
            } finally {
                session.close()
            }
        }
    }

    override suspend fun search(scopeLocation: String, query: SearchQuery): EntryList {
        if (query.term.isBlank()) return EntryList(emptyList(), 0)
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            try {
                val share = session.share
                val all = share.listDirectory(scopeLocation)
                    .filterByName(query.term)
                    .map { mapEntry(it) }
                    .filter { it.type !in query.excludeTypes }
                val sorted = when (query.sortBy) {
                    SortField.Title, SortField.IndexNumber, null -> all.sortedBy { it.title }
                    else -> all.sortedBy { it.title }
                }.let { if (query.sortOrder == SortOrder.Descending) it.reversed() else it }
                val page = sorted.drop(query.startIndex).take(query.limit)
                EntryList(items = page, totalCount = all.size)
            } finally {
                session.close()
            }
        }
    }

    override suspend fun getEntries(itemRefs: List<String>): EntryList {
        if (itemRefs.isEmpty()) return EntryList(emptyList(), 0)
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            try {
                val share = session.share
                val items = share.listDirectory("")
                    .filter { it.path in itemRefs }
                    .map { mapEntry(it) }
                EntryList(items = items, totalCount = items.size)
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
                httpClient = httpClient,
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

        override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            val r = file.read(buffer, position, offset, size)
            return if (r <= 0) 0 else r
        }

        override fun close() {
            runCatching { file.close() }
            session.close()
        }
    }

    private fun mapEntry(e: SmbListEntry): EntryInfo {
        val kind = when {
            e.isDirectory -> EntryType.Folder
            isLikelyVideoFile(e.name) -> EntryType.Playable
            isLikelyImageFile(e.name) -> EntryType.Image
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
