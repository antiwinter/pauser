package com.opentune.app.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.opentune.app.ui.home.HomeRoute
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.Routes
import com.opentune.content.ui.catalog.NavSharedViewModel
import com.opentune.content.ui.catalog.PlayerController
import com.opentune.content.ui.contentRoutes
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
        provider: String,
        endpointId: String,
        entry: EntryInfo,
    ) {
        sharedVm.cache(entry)
        nav.navigate(Routes.browse(provider, endpointId, entry))
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
                        id = location,
                        title = location,
                        type = "Root",
                        collectionType = cmd.collectionType,
                    )
                    cacheAndBrowse(cmd.provider, cmd.endpointId, entry)
                }
                is NavCommand.Detail -> {
                    val entry = EntryInfo(id = cmd.itemRef, title = cmd.itemRef, type = "Unknown")
                    sharedVm.cache(entry)
                    nav.navigate(Routes.detail(cmd.provider, cmd.endpointId, cmd.itemRef, entry))
                }
                is NavCommand.Player -> {
                    val entry = EntryInfo(id = cmd.itemRef, title = cmd.itemRef, type = "Unknown")
                    sharedVm.cache(entry)
                    nav.navigate(Routes.player(cmd.provider, cmd.endpointId, cmd.itemRef, entry))
                }
                is NavCommand.Image -> nav.navigate(Routes.imageViewer(cmd.provider, cmd.endpointId, cmd.itemRef))
                is NavCommand.Search -> nav.navigate(Routes.search(cmd.provider, cmd.endpointId, cmd.scopeLocation))
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeRoute(
                onAddProvider = { pt -> nav.navigate(Routes.providerEdit(pt)) },
                onOpenBrowse = { pt, sid ->
                    val entry = EntryInfo(id = "", title = "", type = "Root")
                    cacheAndBrowse(pt, sid, entry)
                },
                onEditProvider = { pt, sid -> nav.navigate(Routes.providerEdit(pt, sid)) },
                onAddProxy = { pt -> nav.navigate(ProxyRoutes.proxyEdit(pt)) },
                onEditProxy = { pt, id -> nav.navigate(ProxyRoutes.proxyEdit(pt, id)) },
            )
        }
        contentRoutes(nav, sharedVm, playerController)
        proxyRoutes(nav)
    }
}
