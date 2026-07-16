package com.insomnia.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.insomnia.app.ui.home.AddEndpointRoute
import com.insomnia.app.ui.home.HomeRoute
import com.insomnia.content.contract.EndpointClientRegistryHolder
import com.insomnia.content.contract.EntryInfo
import com.insomnia.content.ui.Routes
import com.insomnia.content.ui.catalog.NavSharedViewModel
import com.insomnia.content.ui.catalog.player.PlayerController
import com.insomnia.content.ui.contentRoutes
import com.insomnia.app.ui.settings.SettingsScreen
import com.insomnia.core.osd.GlobalOsdOverlay
import com.insomnia.player.PlayerSurfaceState
import com.insomnia.player.ui.tv.TvPlayerSurface
import com.insomnia.player.ui.tv.TvLivePlayerSurface
import com.insomnia.proxy.ui.ProxyRoutes
import com.insomnia.proxy.ui.proxyRoutes
import com.insomnia.server.debug.AppCommand
import com.insomnia.server.debug.AppCommandBridge

@Composable
fun InsomniaNavHost() {
    val nav = rememberNavController()
    val sharedVm: NavSharedViewModel = viewModel()
    val playerController: PlayerController = viewModel()

    fun cacheAndBrowse(
        endpointId: String,
        entry: EntryInfo,
    ) {
        sharedVm.cache(entry)
        nav.navigate(Routes.browse(endpointId, entry))
    }

    LaunchedEffect(nav) {
        for (cmd in AppCommandBridge.commands) {
            when (cmd) {
                AppCommand.Home -> nav.navigate(Routes.HOME) {
                    popUpTo(Routes.HOME) { inclusive = true }
                }
                is AppCommand.Browse -> {
                    val location = cmd.location ?: ""
                    val entry = EntryInfo(
                        ref = location,
                        title = location,
                        type = "Root",
                    )
                    cacheAndBrowse(cmd.endpointId, entry)
                }
                is AppCommand.Detail -> {
                    val entry = EntryInfo(ref = cmd.itemRef, title = cmd.itemRef, type = "Unknown")
                    sharedVm.cache(entry)
                    nav.navigate(Routes.detail(cmd.endpointId, entry))
                }
                is AppCommand.Player -> {
                    val entry = EntryInfo(ref = cmd.itemRef, title = cmd.itemRef, type = "Unknown")
                    sharedVm.cache(entry)
                    val client = EndpointClientRegistryHolder.get().getOrCreate(cmd.endpointId)
                        ?: throw IllegalStateException("No provider instance for ${cmd.endpointId}")
                    playerController.setClient(client)
                    playerController.prepare(entry, cmd.startMs)
                    playerController.play()
                }
                is AppCommand.Image -> nav.navigate(Routes.imageViewer(cmd.endpointId, cmd.itemRef))
                is AppCommand.Seek -> playerController.seek(cmd.positionMs, cmd.deltaMs)
            }
        }
    }

    val surfaceState by playerController.surfaceStateFlow.collectAsState()
    val isShown = surfaceState != PlayerSurfaceState.HIDE

    Box(modifier = Modifier.fillMaxSize()) {
        // When the player overlay is shown, block all Compose key events from reaching
        // background screens. Without this, queued key events (e.g., the ACTION_UP of the
        // CENTER press that started playback) can activate buttons like Search while the
        // player is loading focus.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isShown) Modifier.onPreviewKeyEvent { true } else Modifier),
        ) {
            NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeRoute(
                    onAddEndpoint = { nav.navigate(Routes.ADD_ENDPOINT) },
                    onOpenBrowse = { _, sid ->
                        val entry = EntryInfo(ref = "", title = "", type = "Root")
                        cacheAndBrowse(sid, entry)
                    },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onEditProvider = { pt, sid -> nav.navigate(Routes.providerEdit(pt, sid)) },
                    onEditProxy = { pt, id -> nav.navigate(ProxyRoutes.proxyEdit(pt, id)) },
                    onCtrlProxy = { pt, id -> nav.navigate(ProxyRoutes.proxyCtrl(pt, id)) },
                )
            }
            composable(Routes.ADD_ENDPOINT) {
                AddEndpointRoute(
                    onSelectProvider = { pt ->
                        nav.navigate(Routes.providerEdit(pt))
                    },
                    onSelectProxy = { pt ->
                        nav.navigate(ProxyRoutes.proxyEdit(pt))
                    },
                )
            }
            contentRoutes(nav, sharedVm, playerController)
            proxyRoutes(nav)
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
        }
        }

        when (surfaceState) {
            PlayerSurfaceState.LIVE -> TvLivePlayerSurface(
                controller = playerController,
                onBack = { playerController.stop() },
            )
            PlayerSurfaceState.VOD -> TvPlayerSurface(
                controller = playerController,
                onBack = { playerController.stop() },
            )
            PlayerSurfaceState.HIDE -> {}
        }

        GlobalOsdOverlay()
    }
}
