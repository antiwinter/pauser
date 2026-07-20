package com.insomnia.content.ui

import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.insomnia.content.ui.catalog.browse.BrowseRoute
import com.insomnia.content.ui.catalog.browse.BrowseViewModel
import com.insomnia.content.ui.catalog.NavSharedViewModel
import com.insomnia.content.ui.catalog.player.PlayerController
import com.insomnia.content.ui.catalog.detail.DetailRoute
import com.insomnia.content.ui.catalog.detail.DetailViewModel
import com.insomnia.content.ui.catalog.ImageViewerRoute
import com.insomnia.content.ui.catalog.live.LiveRoute
import com.insomnia.content.contract.EndpointClient
import com.insomnia.content.contract.EndpointClientRegistryHolder
import com.insomnia.content.contract.InsomniaProviderRegistryHolder
import com.insomnia.core.form.contract.FormFieldKind
import com.insomnia.proxy.contract.WrappedProxyClient
import com.insomnia.core.form.contract.QrResult
import com.insomnia.content.ui.providers.EndpointConfigRepository
import com.insomnia.core.form.ProviderFormRoute

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
        val provider   = InsomniaProviderRegistryHolder.get().provider(protocol)
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
                client.proxyClient = WrappedProxyClient(EndpointClientRegistryHolder.get().buildProxyClient(proxyId))
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
            navArgument("querySpecJson") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val querySpecJson = backStackEntry.arguments!!.getString("querySpecJson")!!
        val queries = decodeQuerySpecList(querySpecJson) 
            ?: listOf(decodeQuerySpec(querySpecJson) ?: error("Failed to decode QuerySpec from: $querySpecJson"))

        val browseVm: BrowseViewModel = viewModel(
            factory = BrowseViewModel.factory(),
        )

        BrowseRoute(
            nav = nav,
            initialSpecs = queries,
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
        Routes.LIVE_PLAYER,
        listOf(
            navArgument("endpointId") { type = NavType.StringType },
            navArgument("ref") { type = NavType.StringType },
        ),
    ) {
        val endpointId = it.arguments!!.getString("endpointId")!!
        val ref = it.arguments!!.getString("ref")!!
        val entryInfo = sharedVm.get(ref)

        LiveRoute(
            nav = nav,
            endpointId = endpointId,
            initialInfo = entryInfo,
            livepakRef = ref,
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
        com.insomnia.content.ui.catalog.AudioUnsupportedScreen(
            onBack = { nav.popBackStack() },
        )
    }
}
