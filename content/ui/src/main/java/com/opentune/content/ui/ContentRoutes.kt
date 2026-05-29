package com.opentune.content.ui

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.opentune.content.ui.catalog.BrowseRoute
import com.opentune.content.ui.catalog.CatalogNav
import com.opentune.content.ui.catalog.DetailRoute
import com.opentune.content.ui.catalog.ImageViewerRoute
import com.opentune.content.ui.catalog.PlayerRoute
import com.opentune.content.ui.catalog.SearchRoute
import com.opentune.content.ui.catalog.SettingsScreen
import com.opentune.content.contract.EndpointClient
import com.opentune.content.contract.EndpointClientRegistryHolder
import com.opentune.content.contract.OpenTuneProviderRegistryHolder
import com.opentune.core.form.contract.FormFieldKind
import com.opentune.core.form.contract.QrResult
import com.opentune.content.ui.providers.EndpointConfigRepository
import com.opentune.core.form.ProviderFormRoute

fun NavGraphBuilder.contentRoutes(nav: NavHostController) {
    composable(
        Routes.PROVIDER_EDIT,
        listOf(
            navArgument("protocol") { type = NavType.StringType },
            navArgument("endpointId") { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) {
        val protocol   = it.arguments!!.getString("protocol")!!
        val endpointId = it.arguments!!.getString("endpointId")
        val isAdd      = endpointId == null
        val provider   = OpenTuneProviderRegistryHolder.get().provider(protocol)
        val fields     = provider.getFieldsSpec()
        val hasQr      = isAdd && fields.any { f -> f.kind == FormFieldKind.QrCode }
        val qrClientRef = remember { arrayOfNulls<EndpointClient>(1) }
        ProviderFormRoute(
            fields   = fields,
            draftKey = if (isAdd) protocol else null,
            onLoad   = if (!isAdd) {
                {
                    EndpointConfigRepository.loadEditFields(protocol, endpointId) to
                    EndpointConfigRepository.loadEditProxyId(endpointId)
                }
            } else null,
            onSubmit = { values, proxyId ->
                if (isAdd) EndpointConfigRepository.submitAdd(protocol, values, proxyId)
                else       EndpointConfigRepository.submitEdit(protocol, endpointId, values, proxyId)
            },
            onGetQr = if (hasQr) { proxyId ->
                val client = provider.createClient(emptyMap(), OpenTuneProviderRegistryHolder.get().platformCapabilities)
                client.httpClient = EndpointClientRegistryHolder.get().buildHttpClient(proxyId)
                qrClientRef[0] = client
                client.getQr()
            } else null,
            onPollQr = if (hasQr) { token ->
                qrClientRef[0]?.pollQr(token) ?: QrResult.Error("no client")
            } else null,
            onDone = { nav.popBackStack() },
            onDelete = if (!isAdd) {
                { EndpointConfigRepository.removeEndpoint(endpointId) }
            } else null,
        )
    }
    composable(
        Routes.BROWSE,
        listOf(
            navArgument("provider") { type = NavType.StringType },
            navArgument("endpointId") { type = NavType.StringType },
            navArgument("location") { type = NavType.StringType },
        ),
    ) {
        BrowseRoute(
            nav = nav,
            protocol = it.arguments!!.getString("provider")!!,
            endpointId = it.arguments!!.getString("endpointId")!!,
            locationEncoded = it.arguments!!.getString("location")!!,
        )
    }
    composable(
        Routes.DETAIL,
        listOf(
            navArgument("provider") { type = NavType.StringType },
            navArgument("endpointId") { type = NavType.StringType },
            navArgument("itemRef") { type = NavType.StringType },
            navArgument("infoJson") { type = NavType.StringType; nullable = true },
        ),
    ) {
        val infoJson = it.arguments!!.getString("infoJson")
        DetailRoute(
            nav = nav,
            protocol = it.arguments!!.getString("provider")!!,
            endpointId = it.arguments!!.getString("endpointId")!!,
            itemRefEncoded = it.arguments!!.getString("itemRef")!!,
            initialInfo = if (!infoJson.isNullOrBlank()) decodeEntryInfo(infoJson) else null,
        )
    }
    composable(
        Routes.SEARCH,
        listOf(
            navArgument("provider") { type = NavType.StringType },
            navArgument("endpointId") { type = NavType.StringType },
            navArgument("scopeLocation") { type = NavType.StringType },
        ),
    ) {
        SearchRoute(
            nav = nav,
            protocol = it.arguments!!.getString("provider")!!,
            endpointId = it.arguments!!.getString("endpointId")!!,
            scopeLocationEncoded = it.arguments!!.getString("scopeLocation")!!,
        )
    }
    composable(
        Routes.PLAYER,
        listOf(
            navArgument("provider") { type = NavType.StringType },
            navArgument("endpointId") { type = NavType.StringType },
            navArgument("itemRef") { type = NavType.StringType },
            navArgument("startMs") { type = NavType.LongType },
            navArgument("infoJson") { type = NavType.StringType; nullable = true },
        ),
    ) {
        val infoJson = it.arguments!!.getString("infoJson")
        PlayerRoute(
            protocol = it.arguments!!.getString("provider")!!,
            endpointId = it.arguments!!.getString("endpointId")!!,
            itemRefDecoded = CatalogNav.decodeSegment(it.arguments!!.getString("itemRef")!!),
            startMs = it.arguments!!.getLong("startMs"),
            entryInfo = if (!infoJson.isNullOrBlank()) decodeEntryInfo(infoJson) else null,
            onExit = { nav.popBackStack() },
        )
    }
    composable(Routes.SETTINGS) {
        SettingsScreen(onBack = { nav.popBackStack() })
    }
    composable(
        Routes.IMAGE_VIEWER,
        listOf(
            navArgument("provider") { type = NavType.StringType },
            navArgument("endpointId") { type = NavType.StringType },
            navArgument("itemRef") { type = NavType.StringType },
        ),
    ) {
        ImageViewerRoute(
            endpointId = it.arguments!!.getString("endpointId")!!,
            itemRefDecoded = CatalogNav.decodeSegment(it.arguments!!.getString("itemRef")!!),
            onExit = { nav.popBackStack() },
        )
    }
}
