package com.opentune.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.opentune.app.ui.home.AddEndpointRoute
import com.opentune.app.ui.home.HomeRoute
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.Routes
import com.opentune.content.ui.catalog.NavSharedViewModel
import com.opentune.content.ui.catalog.player.PlayerController
import com.opentune.content.ui.contentRoutes
import com.opentune.app.ui.settings.SettingsScreen
import com.opentune.core.osd.GlobalOsdOverlay
import com.opentune.player.ui.tv.TvPlayerSurface
import com.opentune.proxy.ui.ProxyRoutes
import com.opentune.proxy.ui.proxyRoutes
import com.opentune.server.debug.NavCommand
import com.opentune.server.debug.NavigationBridge

@Composable
fun OpenTuneNavHost() {
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
        for (cmd in NavigationBridge.commands) {
            when (cmd) {
                NavCommand.Home -> nav.navigate(Routes.HOME) {
                    popUpTo(Routes.HOME) { inclusive = true }
                }
                is NavCommand.Browse -> {
                    val location = cmd.location ?: ""
                    val entry = EntryInfo(
                        ref = location,
                        title = location,
                        type = "Root",
                    )
                    cacheAndBrowse(cmd.endpointId, entry)
                }
                is NavCommand.Detail -> {
                    val entry = EntryInfo(ref = cmd.itemRef, title = cmd.itemRef, type = "Unknown")
                    sharedVm.cache(entry)
                    nav.navigate(Routes.detail(cmd.endpointId, entry))
                }
                is NavCommand.Player -> {
                    val entry = EntryInfo(ref = cmd.itemRef, title = cmd.itemRef, type = "Unknown")
                    sharedVm.cache(entry)
                    val client = EndpointClientRegistryHolder.get().getOrCreate(cmd.endpointId)
                        ?: throw IllegalStateException("No provider instance for ${cmd.endpointId}")
                    playerController.setClient(client)
                    playerController.prepare(entry, cmd.startMs)
                    playerController.play()
                }
                is NavCommand.Image -> nav.navigate(Routes.imageViewer(cmd.endpointId, cmd.itemRef))
                is NavCommand.Search -> nav.navigate(Routes.search(cmd.endpointId, cmd.scopeLocation))
            }
        }
    }

    val isShown by playerController.isShownFlow.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
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

        if (isShown) {
            TvPlayerSurface(
                controller = playerController,
                onBack = { playerController.stop() },
            )
        }

        GlobalOsdOverlay()
    }
}
