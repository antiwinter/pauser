package com.opentune.proxy.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.opentune.content.ui.Routes
import com.opentune.content.ui.providers.ProxyRepository
import com.opentune.core.form.ProviderFormRoute
import com.opentune.proxy.contract.ProxyProviderRegistryHolder

fun NavGraphBuilder.proxyRoutes(nav: NavHostController) {
    composable(
        Routes.PROXY_ADD,
        listOf(navArgument("proxyType") { type = NavType.StringType }),
    ) {
        val proxyType = it.arguments!!.getString("proxyType")!!
        val proxy = ProxyProviderRegistryHolder.get().proxy(proxyType)
        ProviderFormRoute(
            fields = proxy.getFieldsSpec(),
            onSubmit = { values, _ -> ProxyRepository.submitAdd(proxyType, values) },
            onDone = { nav.popBackStack() },
        )
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
        val proxy = ProxyProviderRegistryHolder.get().proxy(proxyType)
        ProviderFormRoute(
            fields = proxy.getFieldsSpec(),
            onLoad = {
                val fields = ProxyRepository.loadEditFields(proxyType, proxyId)
                fields to null
            },
            onSubmit = { values, _ -> ProxyRepository.submitEdit(proxyType, proxyId, values) },
            onDone = { nav.popBackStack() },
        )
    }
}
