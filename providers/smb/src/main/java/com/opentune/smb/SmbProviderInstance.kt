package com.opentune.smb

import android.content.Context
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
import com.opentune.provider.OpenTuneMediaSourceFactory
import com.opentune.provider.OpenTuneProviderInstance
import com.opentune.provider.PlaybackSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.EnumSet
import com.opentune.smb.R as SmbR

private const val SMB_PLAYER_LOG = "OpenTunePlayer"

@UnstableApi
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
        val coverRes = if (video) SmbR.drawable.opentune_smb_video else SmbR.drawable.opentune_smb_file
        return MediaDetailModel(
            itemKey = itemRef,
            title = name,
            synopsis = path,
            cover = MediaArt.DrawableRes(coverRes),
            poster = MediaArt.None,
            canPlay = video,
            resumePositionMs = 0L,
            favoriteSupported = false,
            isFavorite = false,
        )
    }

    override suspend fun resolvePlayback(itemRef: String, startMs: Long, context: Context): PlaybackSpec {
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
            val mainFactory = OpenTuneMediaSourceFactory { progressiveFactory.createMediaSource(mediaItem) }
            val video = isLikelyVideoFile(pathWin.substringAfterLast('\\'))
            val fallbackFactory = if (video) OpenTuneMediaSourceFactory { progressiveFactory.createMediaSource(mediaItem) } else null
            val banner = if (video) context.getString(SmbR.string.smb_audio_unsupported_banner) else null

            PlaybackSpec(
                mediaSourceFactory = mainFactory,
                audioFallbackFactory = fallbackFactory,
                displayTitle = pathWin.substringAfterLast('\\').ifEmpty { pathWin },
                durationMs = null,
                audioFallbackOnly = video,
                hooks = SmbPlaybackHooks,
                audioDecodeUnsupportedBanner = banner,
                initialPositionMs = startMs,
                onPlaybackDispose = { session.close() },
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
        val res = when {
            e.isDirectory -> SmbR.drawable.opentune_smb_folder
            isLikelyVideoFile(e.name) -> SmbR.drawable.opentune_smb_video
            else -> SmbR.drawable.opentune_smb_file
        }
        return MediaListItem(
            id = e.path,
            title = e.name + if (e.isDirectory) "/" else "",
            kind = kind,
            cover = MediaArt.DrawableRes(res),
        )
    }
}
