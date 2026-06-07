package com.opentune.content.ui

import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.opentune.content.ui.catalog.BrowseRoute
import com.opentune.content.ui.catalog.BrowseViewModel
import com.opentune.content.ui.catalog.CatalogNav
import com.opentune.content.ui.catalog.NavSharedViewModel
import com.opentune.content.ui.catalog.PlayerController
import com.opentune.content.ui.catalog.DetailRoute
import com.opentune.content.ui.catalog.DetailViewModel
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

fun NavGraphBuilder.contentRoutes(
    nav: NavHostController,
    sharedVm: NavSharedViewModel,
    playerController: PlayerController,
) {
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
                val client = provider.createClient(emptyMap())
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
            navArgument("id") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val provider = backStackEntry.arguments!!.getString("provider")!!
        val endpointId = backStackEntry.arguments!!.getString("endpointId")!!
        val id = backStackEntry.arguments!!.getString("id")!!
        val entryInfo = sharedVm.get(id)
            ?: error("No EntryInfo cached for id=$id — navigate via cacheAndBrowse()")

        val browseVm: BrowseViewModel = viewModel(
            factory = BrowseViewModel.factory(id),
        )

        BrowseRoute(
            nav = nav,
            protocol = provider,
            endpointId = endpointId,
            initialEntryInfo = entryInfo,
            viewModel = browseVm,
            sharedVm = sharedVm,
        )
    }
    composable(
        Routes.DETAIL,
        listOf(
            navArgument("provider") { type = NavType.StringType },
            navArgument("endpointId") { type = NavType.StringType },
            navArgument("itemRef") { type = NavType.StringType },
            navArgument("id") { type = NavType.StringType },
        ),
    ) {
        val provider = it.arguments!!.getString("provider")!!
        val endpointId = it.arguments!!.getString("endpointId")!!
        val itemRef = it.arguments!!.getString("itemRef")!!
        val id = it.arguments!!.getString("id")!!
        // Prefer cache lookup; fallback to null for backward compat (debug API navigate)
        val entryInfo = sharedVm.get(id)

        val detailVm: DetailViewModel = viewModel(
            factory = DetailViewModel.factory(itemRef),
        )

        DetailRoute(
            nav = nav,
            protocol = provider,
            endpointId = endpointId,
            itemRefEncoded = itemRef,
            initialInfo = entryInfo,
            sharedVm = sharedVm,
            viewModel = detailVm,
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
            sharedVm = sharedVm,
        )
    }
    composable(
        Routes.PLAYER,
        listOf(
            navArgument("provider") { type = NavType.StringType },
            navArgument("endpointId") { type = NavType.StringType },
            navArgument("itemRef") { type = NavType.StringType },
            navArgument("startMs") { type = NavType.LongType },
            navArgument("id") { type = NavType.StringType },
        ),
    ) {
        val id = it.arguments!!.getString("id")!!
        val entryInfo = sharedVm.get(id)
            ?: error("No EntryInfo cached for id=$id — navigate via sharedVm.cache() before navigating to player")

        PlayerRoute(
            protocol = it.arguments!!.getString("provider")!!,
            endpointId = it.arguments!!.getString("endpointId")!!,
            itemRefDecoded = CatalogNav.decodeSegment(it.arguments!!.getString("itemRef")!!),
            startMs = it.arguments!!.getLong("startMs"),
            entryInfo = entryInfo,
            playerController = playerController,
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
    composable(Routes.AUDIO_UNSUPPORTED) {
        com.opentune.content.ui.catalog.AudioUnsupportedScreen(
            onBack = { nav.popBackStack() },
        )
    }
}
