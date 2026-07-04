package com.insomnia.proxy.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.insomnia.content.contract.EndpointClientRegistryHolder
import com.insomnia.content.ui.providers.ProxyRepository
import com.insomnia.core.form.ProviderFormRoute
import com.insomnia.proxy.contract.ProxyClient
import com.insomnia.proxy.contract.ProxyProviderRegistryHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

object ProxyRoutes {
    const val PROXY_EDIT = "proxy_edit/{proxyType}?proxyId={proxyId}"
    const val PROXY_CTRL = "proxy_ctrl/{proxyType}?proxyId={proxyId}"

    fun proxyEdit(proxyType: String, proxyId: String? = null) =
        if (proxyId != null) "proxy_edit/$proxyType?proxyId=${URLEncoder.encode(proxyId, "UTF-8")}"
        else "proxy_edit/$proxyType"

    fun proxyCtrl(proxyType: String, proxyId: String) =
        "proxy_ctrl/$proxyType?proxyId=${URLEncoder.encode(proxyId, "UTF-8")}"
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

    composable(
        ProxyRoutes.PROXY_CTRL,
        listOf(
            navArgument("proxyType") { type = NavType.StringType },
            navArgument("proxyId") { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) {
        val proxyType = it.arguments!!.getString("proxyType")!!
        val proxyId   = it.arguments!!.getString("proxyId")!!
        var client by remember { mutableStateOf<ProxyClient?>(null) }

        LaunchedEffect(proxyId) {
            client = withContext(Dispatchers.IO) {
                EndpointClientRegistryHolder.get().getProxyClient(proxyId)
            }
        }

        client?.ctrlUI?.invoke(
            { nav.navigate(ProxyRoutes.proxyEdit(proxyType, proxyId)) },
            { nav.popBackStack() },
        )
    }
}
