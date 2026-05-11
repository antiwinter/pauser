package com.opentune.smb

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File as SmbFile
import com.opentune.provider.BrowsePageResult
import com.opentune.provider.ItemStream
import com.opentune.provider.MediaArt
import com.opentune.provider.MediaDetailModel
import com.opentune.provider.MediaEntryKind
import com.opentune.provider.MediaListItem
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.EnumSet

private const val SMB_PLAYER_LOG = "OpenTunePlayer"

private fun sha256hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

@UnstableApi
class SmbProviderInstance(
    private val fields: SmbServerFieldsJson,
    private val subtitleCacheDir: File,
) : OpenTuneProviderInstance {

    private fun credentials() = SmbCredentials(
        host = fields.host,
        shareName = fields.shareName,
        username = fields.username,
        password = fields.password,
        domain = fields.domain,
    )

    override suspend fun loadBrowsePage(location: String, startIndex: Int, limit: Int): BrowsePageResult {
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            try {
                val share = session.share ?: error("No share")
                val all = share.listDirectory(location)
                val slice = all.drop(startIndex).take(limit)
                BrowsePageResult(items = slice.map { mapEntry(it) }, totalCount = all.size)
            } finally {
                session.close()
            }
        }
    }

    override suspend fun searchItems(scopeLocation: String, query: String): List<MediaListItem> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            try {
                val share = session.share ?: return@withContext emptyList()
                share.listDirectory(scopeLocation)
                    .filterByName(query)
                    .map { mapEntry(it) }
            } finally {
                session.close()
            }
        }
    }

    override suspend fun loadDetail(itemRef: String): MediaDetailModel {
        val path = itemRef.replace('\\', '/')
        val name = path.substringAfterLast('/').ifEmpty { path }
        val video = isLikelyVideoFile(name)
        return MediaDetailModel(
            title = name,
            overview = path,
            logo = MediaArt.None,
            backdropImages = emptyList(),
            canPlay = video,
            communityRating = null,
            bitrate = null,
            externalUrls = emptyList(),
            productionYear = null,
            providerIds = emptyMap(),
            mediaStreams = emptyList(),
            etag = null,
        )
    }

    override suspend fun resolvePlayback(itemRef: String, startMs: Long): PlaybackSpec {
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            val share = session.share ?: run {
                session.close()
                error("No share")
            }
            val pathWin = itemRef.replace('/', '\\')
            val factory = DataSource.Factory {
                Log.d(SMB_PLAYER_LOG, "[smb] createDataSource pathWin=$pathWin")
                SmbDataSource(share, pathWin)
            }
            val progressiveFactory = ProgressiveMediaSource.Factory(factory)
            val mediaItem = MediaItem.fromUri(Uri.parse("https://local.invalid/video"))

            val rawSubtitles = findSidecarSubtitles(share, itemRef)
            val subtitleTracks = buildList {
                for (track in rawSubtitles) {
                    val smbPath = track.externalRef ?: continue
                    val cached = downloadSubtitleToCache(smbPath) ?: continue
                    add(track.copy(externalRef = cached))
                }
            }

            PlaybackSpec(
                customMediaSourceFactory = { progressiveFactory.createMediaSource(mediaItem) },
                displayTitle = pathWin.substringAfterLast('\\').ifEmpty { pathWin },
                durationMs = null,
                hooks = SmbPlaybackHooks,
                initialPositionMs = startMs,
                onPlaybackDispose = { session.close() },
                subtitleTracks = subtitleTracks,
            )
        }
    }

    override suspend fun <T> withStream(itemRef: String, block: suspend (ItemStream) -> T): T? {
        return withContext(Dispatchers.IO) {
            val session = SmbSession.open(credentials())
            val share = session.share
            val pathWin = itemRef.replace('/', '\\')
            val smbFile = share.openFile(
                pathWin,
                EnumSet.of(AccessMask.GENERIC_READ), null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN, null,
            )
            try {
                block(SmbItemStream(smbFile))
            } finally {
                runCatching { smbFile.close() }
                session.close()
            }
        }
    }

    private class SmbItemStream(private val file: SmbFile) : ItemStream {
        override suspend fun getSize(): Long =
            file.getFileInformation(FileStandardInformation::class.java).endOfFile

        override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            val r = file.read(buffer, position, offset, size)
            return if (r == -1) 0 else r
        }
    }

    private fun mapEntry(e: SmbListEntry): MediaListItem {
        val kind = when {
            e.isDirectory -> MediaEntryKind.Folder
            isLikelyVideoFile(e.name) -> MediaEntryKind.Playable
            else -> MediaEntryKind.Other
        }
        return MediaListItem(
            id = e.path,
            title = e.name + if (e.isDirectory) "/" else "",
            kind = kind,
            cover = MediaArt.None,
        )
    }

    private fun findSidecarSubtitles(share: com.hierynomus.smbj.share.DiskShare, itemRef: String): List<SubtitleTrack> {
        val subtitleExts = setOf(".srt", ".ass", ".ssa", ".vtt", ".sub")
        val parentFolder = itemRef.substringBeforeLast('/', "")
        return runCatching {
            share.listDirectory(parentFolder)
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
        }.getOrElse { emptyList() }
    }

    private suspend fun downloadSubtitleToCache(subtitleRef: String): String? {
        return withStream(subtitleRef) { stream ->
            val size = stream.getSize()
            if (size > 5 * 1024 * 1024) {
                Log.w(SMB_PLAYER_LOG, "Subtitle file too large (${size}B), skipping: $subtitleRef")
                return@withStream null
            }
            val ext = subtitleRef.substringAfterLast('.', "srt").lowercase()
            val hash = sha256hex(subtitleRef).take(16)
            val cacheFile = File(subtitleCacheDir, "$hash.$ext")
            cacheFile.parentFile?.mkdirs()
            val bytes = ByteArray(size.toInt())
            stream.readAt(0L, bytes, 0, bytes.size)
            cacheFile.writeBytes(bytes)
            cacheFile.absolutePath
        }
    }
}
