package com.opentune.content.ui

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
import com.opentune.content.contract.OpenTuneProviderRegistryHolder
import com.opentune.content.ui.providers.EndpointConfigRepository
import com.opentune.core.form.ProviderFormRoute

fun NavGraphBuilder.contentRoutes(nav: NavHostController) {
    composable(
        Routes.PROVIDER_ADD,
        listOf(navArgument("protocol") { type = NavType.StringType }),
    ) {
        val protocol = it.arguments!!.getString("protocol")!!
        val provider = OpenTuneProviderRegistryHolder.get().provider(protocol)
        ProviderFormRoute(
            fields = provider.getFieldsSpec(),
            onDraftSave = { v -> EndpointConfigRepository.saveAddDraft(protocol, v) },
            onSubmit = { values, proxyId ->
                val result = EndpointConfigRepository.submitAdd(protocol, values, proxyId)
                if (result is com.opentune.core.form.SubmitResult.Success) {
                    EndpointConfigRepository.clearAddDraft(protocol)
                }
                result
            },
            onDone = { nav.popBackStack() },
        )
    }
    composable(
        Routes.PROVIDER_EDIT,
        listOf(
            navArgument("protocol") { type = NavType.StringType },
            navArgument("endpointId") { type = NavType.StringType },
        ),
    ) {
        val protocol = it.arguments!!.getString("protocol")!!
        val endpointId = it.arguments!!.getString("endpointId")!!
        val provider = OpenTuneProviderRegistryHolder.get().provider(protocol)
        ProviderFormRoute(
            fields = provider.getFieldsSpec(),
            onLoad = {
                val fields = EndpointConfigRepository.loadEditFields(protocol, endpointId)
                val proxyId = EndpointConfigRepository.loadEditProxyId(endpointId)
                fields to proxyId
            },
            onSubmit = { values, proxyId ->
                EndpointConfigRepository.submitEdit(protocol, endpointId, values, proxyId)
            },
            onDone = { nav.popBackStack() },
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
