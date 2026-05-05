package com.opentune.app.ui.catalog

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.opentune.app.OpenTuneApplication
import com.opentune.app.navigation.Routes
import com.opentune.provider.MediaDetailModel
import com.opentune.storage.MediaStateKey
import com.opentune.storage.get
import com.opentune.storage.upsertFavorite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "OT_Detail"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DetailRoute(
    nav: NavHostController,
    app: OpenTuneApplication,
    providerType: String,
    sourceId: String,
    itemRefEncoded: String,
) {
    val itemRefDecoded = remember(itemRefEncoded) { CatalogNav.decodeSegment(itemRefEncoded) }
    val scope = rememberCoroutineScope()
    val stateKey = remember(providerType, sourceId, itemRefDecoded) {
        MediaStateKey(providerType, sourceId, itemRefDecoded)
    }

    var detail by remember { mutableStateOf<MediaDetailModel?>(null) }
    var isFavorite by remember { mutableStateOf(false) }
    var resumePositionMs by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(providerType, sourceId, itemRefDecoded) {
        loading = true
        error = null
        try {
            val inst = app.instanceRegistry.getOrCreate(sourceId)
                ?: throw IllegalStateException("No provider instance for $sourceId")
            val mediaState = withContext(Dispatchers.IO) {
                app.storageBindings.mediaStateStore.get(stateKey)
            }
            isFavorite = mediaState?.isFavorite ?: false
            resumePositionMs = mediaState?.positionMs ?: 0L
            detail = withContext(Dispatchers.IO) { inst.loadDetail(itemRefDecoded) }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "detail load", e)
            error = e.message
        } finally {
            loading = false
        }
    }

    when {
        error != null -> Text("Error: $error")
        else -> DetailScreen(
            detail = detail,
            loading = loading,
            error = error,
            isFavorite = isFavorite,
            resumePositionMs = resumePositionMs,
            onBack = { nav.popBackStack() },
            onPlayFromStart = {
                nav.navigate(Routes.player(providerType, sourceId, itemRefDecoded, 0L))
            },
            onResume = {
                nav.navigate(Routes.player(providerType, sourceId, itemRefDecoded, resumePositionMs))
            },
            onToggleFavorite = {
                scope.launch {
                    val newVal = !isFavorite
                    isFavorite = newVal
                    try {
                        withContext(Dispatchers.IO) {
                            app.storageBindings.mediaStateStore.upsertFavorite(stateKey, newVal)
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "favorite toggle", e)
                        isFavorite = !newVal
                    }
                }
            },
        )
    }
}
