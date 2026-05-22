package com.opentune.proxy.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.opentune.content.ui.Routes
import com.opentune.content.ui.config.FormEntityType
import com.opentune.content.ui.config.ProviderFormRoute

fun NavGraphBuilder.proxyRoutes(nav: NavHostController) {
    composable(
        Routes.PROXY_ADD,
        listOf(navArgument("proxyType") { type = NavType.StringType }),
    ) {
        val proxyType = it.arguments!!.getString("proxyType")!!
        ProviderFormRoute(entityType = FormEntityType.PROXY, protocol = proxyType, onDone = { nav.popBackStack() })
    }
    composable(
        Routes.PROXY_EDIT,
        listOf(
            navArgument("proxyType") { type = NavType.StringType },
            navArgument("proxyId") { type = NavType.StringType },
        ),
    ) {
        val proxyType = it.arguments!!.getString("proxyType")!!
        val proxyId = it.arguments!!.getString("proxyId")!!
        ProviderFormRoute(entityType = FormEntityType.PROXY, protocol = proxyType, existingId = proxyId, onDone = { nav.popBackStack() })
    }
}
