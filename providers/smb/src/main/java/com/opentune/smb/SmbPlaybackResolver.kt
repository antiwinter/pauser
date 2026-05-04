package com.opentune.smb

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.opentune.provider.OpenTuneMediaSourceFactory
import com.opentune.provider.OpenTuneProviderIds
import com.opentune.provider.PlaybackResolveDeps
import com.opentune.provider.PlaybackSpec
import com.opentune.provider.progressPersistenceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SMB_PLAYER_LOG = "OpenTunePlayer"

@UnstableApi
object SmbPlaybackResolver {

    suspend fun resolve(
        deps: PlaybackResolveDeps,
        sourceId: Long,
        itemRef: String,
        @Suppress("UNUSED_PARAMETER") startMs: Long,
    ): PlaybackSpec = withContext(Dispatchers.IO) {
        val row = deps.serverStore.get(sourceId) ?: error("SMB source not found")
        require(row.providerId == OpenTuneProviderIds.FILE_SHARE) { "Wrong provider for SMB resolver" }
        val fields = SmbServerFieldsJson.parse(row.fieldsJson)
        val session = SmbSession.open(
            SmbCredentials(
                host = fields.host,
                shareName = fields.shareName,
                username = fields.username,
                password = fields.password,
                domain = fields.domain,
            ),
        )
        val share = session.share ?: run {
            session.close()
            error("No share")
        }
        val pathWin = itemRef.replace('/', '\\')
        val resumeKey = progressPersistenceKey(OpenTuneProviderIds.FILE_SHARE, sourceId, pathWin)
        val stored = deps.resumeAccessor.readPositionMs(resumeKey)
        val initialPositionMs = if (stored >= 0L) stored else 0L

        val factory = DataSource.Factory {
            Log.d(SMB_PLAYER_LOG, "[smb] createDataSource pathWin=$pathWin")
            SmbDataSource(share, pathWin)
        }
        val progressiveFactory = ProgressiveMediaSource.Factory(factory)
        val mediaItem = MediaItem.fromUri(Uri.parse("https://local.invalid/video"))

        val mainFactory = OpenTuneMediaSourceFactory {
            progressiveFactory.createMediaSource(mediaItem)
        }
        val video = isLikelyVideoFile(pathWin.substringAfterLast('\\'))
        val fallbackFactory = if (video) {
            OpenTuneMediaSourceFactory {
                progressiveFactory.createMediaSource(mediaItem)
            }
        } else {
            null
        }

        val banner = if (video) {
            deps.androidContext.getString(R.string.smb_audio_unsupported_banner)
        } else {
            null
        }

        PlaybackSpec(
            mediaSourceFactory = mainFactory,
            audioFallbackFactory = fallbackFactory,
            displayTitle = pathWin.substringAfterLast('\\').ifEmpty { pathWin },
            resumeKey = resumeKey,
            durationMs = null,
            audioFallbackOnly = video,
            hooks = SmbPlaybackHooks,
            audioDecodeUnsupportedBanner = banner,
            initialPositionMs = initialPositionMs,
            onPlaybackDispose = { session.close() },
        )
    }
}
