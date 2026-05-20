package com.opentune.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opentune.app.OpenTuneApplication
import com.opentune.app.ui.catalog.BrowseRoute
import com.opentune.app.ui.catalog.CatalogNav
import com.opentune.app.ui.catalog.DetailRoute
import com.opentune.app.ui.catalog.ImageViewerRoute
import com.opentune.app.ui.catalog.PlayerRoute
import com.opentune.app.ui.catalog.SearchRoute
import com.opentune.app.ui.catalog.SettingsScreen
import com.opentune.app.ui.config.EndpointAddRoute
import com.opentune.app.ui.config.EndpointEditRoute
import com.opentune.app.ui.home.HomeRoute
import com.opentune.provider.EntryInfo
import com.opentune.server.debug.NavCommand
import com.opentune.server.debug.NavigationBridge
import java.net.URLEncoder
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object Routes {

    /** [URLEncoder.encode] with Charset is API 33+; use charset name for older Android TV devices. */
    private const val UrlCharset = "UTF-8"
    const val HOME = "home"
    const val BROWSE = "browse/{provider}/{endpointId}/{location}"
    const val DETAIL = "detail/{provider}/{endpointId}/{itemRef}/{infoJson}"
    const val PLAYER = "player/{provider}/{endpointId}/{itemRef}/{startMs}/{infoJson}"
    const val SEARCH = "search/{provider}/{endpointId}/{scopeLocation}"
    const val PROVIDER_ADD = "provider_add/{protocol}"
    const val PROVIDER_EDIT = "provider_edit/{protocol}/{endpointId}"
    const val SETTINGS = "settings"
    const val IMAGE_VIEWER = "image_viewer/{provider}/{endpointId}/{itemRef}"

    fun providerAdd(protocol: String) = "provider_add/$protocol"

    fun providerEdit(protocol: String, endpointId: String) =
        "provider_edit/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}"

    fun browse(protocol: String, endpointId: String, locationRaw: String) =
        "browse/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(locationRaw, UrlCharset)}"

    fun detail(
        protocol: String,
        endpointId: String,
        itemRefRaw: String,
        infoJson: String? = null,
    ) =
        "detail/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}/${URLEncoder.encode(infoJson ?: "", UrlCharset)}"

    fun player(protocol: String, endpointId: String, itemRefRaw: String, startMs: Long, info: EntryInfo? = null) =
        "player/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}/$startMs/${URLEncoder.encode(info?.let { navJson.encodeToString(it) } ?: "", UrlCharset)}"

    fun search(protocol: String, endpointId: String, scopeLocationRaw: String) =
        "search/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(scopeLocationRaw, UrlCharset)}"

    fun imageViewer(protocol: String, endpointId: String, itemRefRaw: String) =
        "image_viewer/$protocol/${URLEncoder.encode(endpointId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}"
}

private val navJson = Json { ignoreUnknownKeys = true }

internal fun EntryInfo.toJson(): String = navJson.encodeToString(this)

internal fun decodeEntryInfo(json: String): EntryInfo? =
    runCatching { navJson.decodeFromString<EntryInfo>(json) }.getOrNull()

@Composable
fun OpenTuneNavHost() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as OpenTuneApplication

    LaunchedEffect(nav) {
        for (cmd in NavigationBridge.commands) {
            when (cmd) {
                NavCommand.Home -> nav.navigate(Routes.HOME) {
                    popUpTo(Routes.HOME) { inclusive = true }
                }
                is NavCommand.Browse -> nav.navigate(Routes.browse(cmd.provider, cmd.endpointId, cmd.location ?: ""))
                is NavCommand.Detail -> nav.navigate(Routes.detail(cmd.provider, cmd.endpointId, cmd.itemRef))
                is NavCommand.Player -> nav.navigate(Routes.player(cmd.provider, cmd.endpointId, cmd.itemRef, cmd.startMs))
                is NavCommand.Image -> nav.navigate(Routes.imageViewer(cmd.provider, cmd.endpointId, cmd.itemRef))
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeRoute(
                onAddProvider = { pt -> nav.navigate(Routes.providerAdd(pt)) },
                onOpenBrowse = { pt, sid, path ->
                    nav.navigate(Routes.browse(pt, sid, path))
                },
                onEditProvider = { pt, sid -> nav.navigate(Routes.providerEdit(pt, sid)) },
            )
        }
        composable(
            Routes.PROVIDER_ADD,
            listOf(navArgument("protocol") { type = NavType.StringType }),
        ) {
            val protocol = it.arguments!!.getString("protocol")!!
            EndpointAddRoute(
                protocol = protocol,
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
            EndpointEditRoute(
                protocol = protocol,
                endpointId = endpointId,
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
            val protocol = it.arguments!!.getString("provider")!!
            val endpointId = it.arguments!!.getString("endpointId")!!
            val location = it.arguments!!.getString("location")!!
            BrowseRoute(
                nav = nav,
                app = app,
                protocol = protocol,
                endpointId = endpointId,
                locationEncoded = location,
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
            val protocol = it.arguments!!.getString("provider")!!
            val endpointId = it.arguments!!.getString("endpointId")!!
            val itemRef = it.arguments!!.getString("itemRef")!!
            val infoJsonStr = it.arguments!!.getString("infoJson")
            val initialInfo = if (!infoJsonStr.isNullOrBlank()) decodeEntryInfo(infoJsonStr) else null
            DetailRoute(
                nav = nav,
                app = app,
                protocol = protocol,
                endpointId = endpointId,
                itemRefEncoded = itemRef,
                initialInfo = initialInfo,
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
            val protocol = it.arguments!!.getString("provider")!!
            val endpointId = it.arguments!!.getString("endpointId")!!
            val scope = it.arguments!!.getString("scopeLocation")!!
            SearchRoute(
                nav = nav,
                app = app,
                protocol = protocol,
                endpointId = endpointId,
                scopeLocationEncoded = scope,
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
            val protocol = it.arguments!!.getString("provider")!!
            val endpointId = it.arguments!!.getString("endpointId")!!
            val itemRef = it.arguments!!.getString("itemRef")!!
            val startMs = it.arguments!!.getLong("startMs")
            val infoJsonStr = it.arguments!!.getString("infoJson")
            val itemRefDecoded = CatalogNav.decodeSegment(itemRef)
            val entryInfo = if (!infoJsonStr.isNullOrBlank()) decodeEntryInfo(infoJsonStr) else null
            PlayerRoute(
                app = app,
                protocol = protocol,
                endpointId = endpointId,
                itemRefDecoded = itemRefDecoded,
                startMs = startMs,
                entryInfo = entryInfo,
                onExit = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                app = app,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.IMAGE_VIEWER,
            listOf(
                navArgument("provider") { type = NavType.StringType },
                navArgument("endpointId") { type = NavType.StringType },
                navArgument("itemRef") { type = NavType.StringType },
            ),
        ) {
            val endpointId = it.arguments!!.getString("endpointId")!!
            val itemRef = it.arguments!!.getString("itemRef")!!
            val itemRefDecoded = CatalogNav.decodeSegment(itemRef)
            ImageViewerRoute(
                app = app,
                endpointId = endpointId,
                itemRefDecoded = itemRefDecoded,
                onExit = { nav.popBackStack() },
            )
        }
    }
}
