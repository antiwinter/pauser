package com.opentune.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.opentune.app.ui.home.HomeRoute
import com.opentune.content.contract.EntryInfo
import com.opentune.content.ui.Routes
import com.opentune.content.ui.contentRoutes
import com.opentune.proxy.ui.ProxyRoutes
import com.opentune.proxy.ui.proxyRoutes
import com.opentune.server.debug.NavCommand
import com.opentune.server.debug.NavigationBridge

@Composable
fun OpenTuneNavHost() {
    val nav = rememberNavController()

    LaunchedEffect(nav) {
        for (cmd in NavigationBridge.commands) {
            when (cmd) {
                NavCommand.Home -> nav.navigate(Routes.HOME) {
                    popUpTo(Routes.HOME) { inclusive = true }
                }
                is NavCommand.Browse -> nav.navigate(
                    Routes.browse(cmd.provider, cmd.endpointId,
                        EntryInfo(id = cmd.location ?: "", title = "", type = "Root", collectionType = cmd.collectionType))
                )
                is NavCommand.Detail -> nav.navigate(Routes.detail(cmd.provider, cmd.endpointId, cmd.itemRef))
                is NavCommand.Player -> nav.navigate(Routes.player(cmd.provider, cmd.endpointId, cmd.itemRef, cmd.startMs))
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
                    nav.navigate(Routes.browse(pt, sid, EntryInfo(id = "", title = "", type = "Root")))
                },
                onEditProvider = { pt, sid -> nav.navigate(Routes.providerEdit(pt, sid)) },
                onAddProxy = { pt -> nav.navigate(ProxyRoutes.proxyEdit(pt)) },
                onEditProxy = { pt, id -> nav.navigate(ProxyRoutes.proxyEdit(pt, id)) },
            )
        }
        contentRoutes(nav)
        proxyRoutes(nav)
    }
}
