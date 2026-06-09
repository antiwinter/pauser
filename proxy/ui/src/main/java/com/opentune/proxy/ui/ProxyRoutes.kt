package com.opentune.proxy.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.opentune.content.ui.providers.ProxyRepository
import com.opentune.core.form.ProviderFormRoute
import com.opentune.proxy.contract.ProxyProviderRegistryHolder
import java.net.URLEncoder

object ProxyRoutes {
    const val PROXY_EDIT = "proxy_edit/{proxyType}?proxyId={proxyId}"

    fun proxyEdit(proxyType: String, proxyId: String? = null) =
        if (proxyId != null) "proxy_edit/$proxyType?proxyId=${URLEncoder.encode(proxyId, "UTF-8")}"
        else "proxy_edit/$proxyType"
}

fun NavGraphBuilder.proxyRoutes(nav: NavHostController) {
    composable(
        ProxyRoutes.PROXY_EDIT,
        listOf(
            navArgument("proxyType") { type = NavType.StringType },
            navArgument("proxyId") { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) {
        val proxyType = it.arguments!!.getString("proxyType")!!
        val proxyId   = it.arguments!!.getString("proxyId")
        val proxy     = ProxyProviderRegistryHolder.get().proxy(proxyType)
        ProviderFormRoute(
            fields   = proxy.getFieldsSpec(),
            onLoad   = if (proxyId != null) {
                { ProxyRepository.loadEditFields(proxyType, proxyId) to null }
            } else null,
            onSubmit = { values, _ ->
                if (proxyId == null) ProxyRepository.submitAdd(proxyType, values)
                else                 ProxyRepository.submitEdit(proxyType, proxyId, values)
            },
            onDone = { nav.popBackStack() },
            onDelete = if (proxyId != null) {
                { ProxyRepository.delete(proxyId); Unit }
            } else null,
        )
    }
}

