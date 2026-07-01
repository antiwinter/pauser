package com.opentune.player.engine

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Process-singleton disk cache wrapped around every playback [androidx.media3.datasource.DataSource]
 * via `CacheDataSource`. Purpose: seek-back without re-fetching over the network/LAN — ExoPlayer
 * discards consumed samples on seek and would otherwise re-load from the DataSource.
 *
 * Cache keys are the source URL. Because stream-relay URLs are now stable
 * (`/relay/{endpointId}?ref=…`, no per-play token), keys are stable across seeks and across
 * replays within the app's lifetime, so a second play of the same item hits the cache.
 *
 * In-memory `CachedContentIndex` (no `media3-database` dependency) — the index lives for the
 * app run; the on-disk spans are reclaimed by the LRU evictor. Cross-restart reuse would need
 * a persistent index; deferred.
 */
@UnstableApi
object PlayerCache {

    private const val MAX_BYTES = 750L * 1024 * 1024

    @Volatile private var cache: SimpleCache? = null

    @Suppress("DEPRECATION") // 2-arg SimpleCache is the only public no-DB constructor in media3 1.5
    fun get(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.cacheDir, "player-media"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            ).also { cache = it }
        }
}
