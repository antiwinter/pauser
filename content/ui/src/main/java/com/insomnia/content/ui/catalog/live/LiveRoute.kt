package com.insomnia.content.ui.catalog.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import com.insomnia.content.contract.EndpointClientRegistryHolder
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.epcache.CachingEndpointClient
import com.insomnia.content.ui.catalog.player.PlayerController
import com.insomnia.player.EntryStateKeys
import com.insomnia.player.ItemListInfo
import com.insomnia.player.PlayerSurfaceState
import com.insomnia.storage.decodeSeriesProgress
import com.insomnia.storage.encodeSeriesProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Intermediate infrastructure route for live streams (a "Livepak"). No screen of its own —
 * it loads all channels and wires them into the shared [PlayerController]. The live surface is
 * rendered by the global player overlay in the NavHost (selected via [PlayerController.surfaceStateFlow],
 * which resolves to LIVE from the channel item type), so LiveRoute only manages state + callbacks.
 * BACK pops this route off the stack, whose [DisposableEffect] resets the controller.
 */
@UnstableApi
@Composable
fun LiveRoute(
    nav: NavHostController,
    endpointId: String,
    initialInfo: EntryInfo?,
    livepakRef: String,
    playerController: PlayerController,
) {
    val scope = rememberCoroutineScope()

    var client by remember { mutableStateOf<CachingEndpointClient?>(null) }
    var channels by remember { mutableStateOf<List<EntryInfo>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }

    fun prepareAndPlay(index: Int) {
        val channel = channels.getOrNull(index) ?: return
        playerController.prepare(channel, startMs = 0L)
        playerController.play()
    }

    // One-shot: resolve client, load channels, start playing the resumed channel.
    LaunchedEffect(endpointId, livepakRef) {
        val c = withContext(Dispatchers.IO) {
            EndpointClientRegistryHolder.get().getOrCreate(endpointId)
        } ?: run {
            Timber.e("LiveRoute: no provider instance for $endpointId")
            return@LaunchedEffect
        }
        client = c
        playerController.setClient(c)
        val loaded = withContext(Dispatchers.IO) {
            c.listEntry(livepakRef, 0, 500).first { it.isComplete }.items
        }
        if (loaded.isEmpty()) {
            Timber.w("LiveRoute: no channels under $livepakRef")
            return@LaunchedEffect
        }
        channels = loaded
        val (_, resume) = decodeSeriesProgress(initialInfo?.userData?.positionMs ?: 0L)
        currentIndex = resume.coerceIn(0, loaded.lastIndex)
        prepareAndPlay(currentIndex)
    }

    // Absolute channel switch by index, with wraparound (up from 0 → last, down from last → 0).
    // currentIndex is snapshot state, so reads/writes here are synchronous and race-free.
    fun switchChannel(index: Int) {
        if (channels.isEmpty()) return
        val target = index.mod(channels.size)
        if (target == currentIndex) return
        currentIndex = target
        prepareAndPlay(target)
        val c = client ?: return
        scope.launch(Dispatchers.IO) {
            c.updateEntryState(livepakRef, EntryStateKeys.POSITION_MS, encodeSeriesProgress(0, target).toString())
        }
    }

    // Keep the player's channel list overlay + switch callback in sync with current index.
    LaunchedEffect(channels, currentIndex) {
        playerController.setItemListCallback(
            cb = { index -> switchChannel(index) },
            info = ItemListInfo(current = currentIndex, names = channels.map { it.title }),
        )
    }

    // Once playback has started, a dismissal (BACK → stop()) pops this route back to Browse.
    val surfaceState by playerController.surfaceStateFlow.collectAsState()
    val isShown = surfaceState != PlayerSurfaceState.HIDE
    var wasShown by remember { mutableStateOf(false) }
    LaunchedEffect(isShown) {
        if (isShown) wasShown = true
        else if (wasShown) nav.popBackStack()
    }

    DisposableEffect(Unit) {
        onDispose { playerController.reset() }
    }

    // Only visible before the first channel loads; once playing, the global overlay covers this.
    if (channels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
