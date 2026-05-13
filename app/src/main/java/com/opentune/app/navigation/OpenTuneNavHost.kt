package com.opentune.app.navigation

import androidx.compose.runtime.Composable
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
import com.opentune.app.ui.catalog.PlayerRoute
import com.opentune.app.ui.catalog.SearchRoute
import com.opentune.app.ui.catalog.SettingsScreen
import com.opentune.app.ui.config.ServerAddRoute
import com.opentune.app.ui.config.ServerEditRoute
import com.opentune.app.ui.home.HomeRoute
import java.net.URLEncoder

object Routes {

    /** [URLEncoder.encode] with Charset is API 33+; use charset name for older Android TV devices. */
    private const val UrlCharset = "UTF-8"
    const val HOME = "home"
    const val BROWSE = "browse/{provider}/{sourceId}/{location}"
    const val DETAIL = "detail/{provider}/{sourceId}/{itemRef}"
    const val PLAYER = "player/{provider}/{sourceId}/{itemRef}/{startMs}"
    const val SEARCH = "search/{provider}/{sourceId}/{scopeLocation}"
    const val PROVIDER_ADD = "provider_add/{protocol}"
    const val PROVIDER_EDIT = "provider_edit/{protocol}/{sourceId}"
    const val SETTINGS = "settings"

    fun providerAdd(protocol: String) = "provider_add/$protocol"

    fun providerEdit(protocol: String, sourceId: String) =
        "provider_edit/$protocol/${URLEncoder.encode(sourceId, UrlCharset)}"

    fun browse(protocol: String, sourceId: String, locationRaw: String) =
        "browse/$protocol/${URLEncoder.encode(sourceId, UrlCharset)}/${URLEncoder.encode(locationRaw, UrlCharset)}"

    fun detail(protocol: String, sourceId: String, itemRefRaw: String) =
        "detail/$protocol/${URLEncoder.encode(sourceId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}"

    fun player(protocol: String, sourceId: String, itemRefRaw: String, startMs: Long) =
        "player/$protocol/${URLEncoder.encode(sourceId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}/$startMs"

    fun search(protocol: String, sourceId: String, scopeLocationRaw: String) =
        "search/$protocol/${URLEncoder.encode(sourceId, UrlCharset)}/${URLEncoder.encode(scopeLocationRaw, UrlCharset)}"
}

@Composable
fun OpenTuneNavHost() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as OpenTuneApplication

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
            ServerAddRoute(
                protocol = protocol,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.PROVIDER_EDIT,
            listOf(
                navArgument("protocol") { type = NavType.StringType },
                navArgument("sourceId") { type = NavType.StringType },
            ),
        ) {
            val protocol = it.arguments!!.getString("protocol")!!
            val sourceId = it.arguments!!.getString("sourceId")!!
            ServerEditRoute(
                protocol = protocol,
                sourceId = sourceId,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.BROWSE,
            listOf(
                navArgument("provider") { type = NavType.StringType },
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("location") { type = NavType.StringType },
            ),
        ) {
            val protocol = it.arguments!!.getString("provider")!!
            val sourceId = it.arguments!!.getString("sourceId")!!
            val location = it.arguments!!.getString("location")!!
            BrowseRoute(
                nav = nav,
                app = app,
                protocol = protocol,
                sourceId = sourceId,
                locationEncoded = location,
            )
        }
        composable(
            Routes.DETAIL,
            listOf(
                navArgument("provider") { type = NavType.StringType },
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("itemRef") { type = NavType.StringType },
            ),
        ) {
            val protocol = it.arguments!!.getString("provider")!!
            val sourceId = it.arguments!!.getString("sourceId")!!
            val itemRef = it.arguments!!.getString("itemRef")!!
            DetailRoute(
                nav = nav,
                app = app,
                protocol = protocol,
                sourceId = sourceId,
                itemRefEncoded = itemRef,
            )
        }
        composable(
            Routes.SEARCH,
            listOf(
                navArgument("provider") { type = NavType.StringType },
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("scopeLocation") { type = NavType.StringType },
            ),
        ) {
            val protocol = it.arguments!!.getString("provider")!!
            val sourceId = it.arguments!!.getString("sourceId")!!
            val scope = it.arguments!!.getString("scopeLocation")!!
            SearchRoute(
                nav = nav,
                app = app,
                protocol = protocol,
                sourceId = sourceId,
                scopeLocationEncoded = scope,
            )
        }
        composable(
            Routes.PLAYER,
            listOf(
                navArgument("provider") { type = NavType.StringType },
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("itemRef") { type = NavType.StringType },
                navArgument("startMs") { type = NavType.LongType },
            ),
        ) {
            val protocol = it.arguments!!.getString("provider")!!
            val sourceId = it.arguments!!.getString("sourceId")!!
            val itemRef = it.arguments!!.getString("itemRef")!!
            val startMs = it.arguments!!.getLong("startMs")
            val itemRefDecoded = CatalogNav.decodeSegment(itemRef)
            PlayerRoute(
                app = app,
                protocol = protocol,
                sourceId = sourceId,
                itemRefDecoded = itemRefDecoded,
                startMs = startMs,
                onExit = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                app = app,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
