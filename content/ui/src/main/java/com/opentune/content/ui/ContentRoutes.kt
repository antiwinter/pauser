package com.opentune.content.ui

import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.opentune.content.ui.catalog.browse.BrowseRoute
import com.opentune.content.ui.catalog.browse.BrowseViewModel
import com.opentune.content.ui.catalog.NavSharedViewModel
import com.opentune.content.ui.catalog.player.PlayerController
import com.opentune.content.ui.catalog.detail.DetailRoute
import com.opentune.content.ui.catalog.detail.DetailViewModel
import com.opentune.content.ui.catalog.ImageViewerRoute
import com.opentune.content.ui.catalog.search.SearchRoute
import com.opentune.content.ui.catalog.search.SearchViewModel
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
                client.proxyClient = EndpointClientRegistryHolder.get().buildProxyClient(proxyId)
                qrClientRef[0] = client
                client.getQr()
            } else null,
            onPollQr = if (hasQr) { token ->
                qrClientRef[0]?.pollQr(token) ?: QrResult.Error("no client")
            } else null,
            onDone = { nav.popBackStack(Routes.HOME, inclusive = false) },
            onDelete = if (!isAdd) {
                { EndpointConfigRepository.removeEndpoint(endpointId) }
            } else null,
        )
    }
    composable(
        Routes.BROWSE,
        listOf(
            navArgument("endpointId") { type = NavType.StringType },
            navArgument("ref") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val endpointId = backStackEntry.arguments!!.getString("endpointId")!!
        val ref = backStackEntry.arguments!!.getString("ref")!!
        val entryInfo = sharedVm.get(ref)
            ?: error("No EntryInfo cached for ref=$ref — navigate via cacheAndBrowse()")

        val browseVm: BrowseViewModel = viewModel(
            factory = BrowseViewModel.factory(ref),
        )

        BrowseRoute(
            nav = nav,
            endpointId = endpointId,
            initialEntryInfo = entryInfo,
            viewModel = browseVm,
            sharedVm = sharedVm,
            playerController = playerController,
        )
    }
    composable(
        Routes.DETAIL,
        listOf(
            navArgument("endpointId") { type = NavType.StringType },
            navArgument("ref") { type = NavType.StringType },
        ),
    ) {
        val endpointId = it.arguments!!.getString("endpointId")!!
        val ref = it.arguments!!.getString("ref")!!
        val entryInfo = sharedVm.get(ref)

        val detailVm: DetailViewModel = viewModel(
            factory = DetailViewModel.factory(ref),
        )

        DetailRoute(
            nav = nav,
            endpointId = endpointId,
            itemRef = ref,
            initialInfo = entryInfo,
            viewModel = detailVm,
            playerController = playerController,
        )
    }
    composable(
        Routes.SEARCH,
        listOf(
            navArgument("endpointId") { type = NavType.StringType },
            navArgument("scopeLocation") { type = NavType.StringType },
        ),
    ) {
        val searchVm: SearchViewModel = viewModel()
        SearchRoute(
            nav = nav,
            endpointId = it.arguments!!.getString("endpointId")!!,
            scopeLocationEncoded = it.arguments!!.getString("scopeLocation")!!,
            sharedVm = sharedVm,
            viewModel = searchVm,
            playerController = playerController,
        )
    }
    composable(
        Routes.IMAGE_VIEWER,
        listOf(
            navArgument("endpointId") { type = NavType.StringType },
            navArgument("itemRef") { type = NavType.StringType },
        ),
    ) {
        ImageViewerRoute(
            endpointId = it.arguments!!.getString("endpointId")!!,
            itemRef = it.arguments!!.getString("itemRef")!!,
            onExit = { nav.popBackStack() },
        )
    }
    composable(Routes.AUDIO_UNSUPPORTED) {
        com.opentune.content.ui.catalog.AudioUnsupportedScreen(
            onBack = { nav.popBackStack() },
        )
    }
}
