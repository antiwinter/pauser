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
    const val PROVIDER_ADD = "provider_add/{providerType}"
    const val PROVIDER_EDIT = "provider_edit/{providerType}/{sourceId}"

    fun providerAdd(providerType: String) = "provider_add/$providerType"

    fun providerEdit(providerType: String, sourceId: String) =
        "provider_edit/$providerType/${URLEncoder.encode(sourceId, UrlCharset)}"

    fun browse(providerType: String, sourceId: String, locationRaw: String) =
        "browse/$providerType/${URLEncoder.encode(sourceId, UrlCharset)}/${URLEncoder.encode(locationRaw, UrlCharset)}"

    fun detail(providerType: String, sourceId: String, itemRefRaw: String) =
        "detail/$providerType/${URLEncoder.encode(sourceId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}"

    fun player(providerType: String, sourceId: String, itemRefRaw: String, startMs: Long) =
        "player/$providerType/${URLEncoder.encode(sourceId, UrlCharset)}/${URLEncoder.encode(itemRefRaw, UrlCharset)}/$startMs"

    fun search(providerType: String, sourceId: String, scopeLocationRaw: String) =
        "search/$providerType/${URLEncoder.encode(sourceId, UrlCharset)}/${URLEncoder.encode(scopeLocationRaw, UrlCharset)}"
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
            listOf(navArgument("providerType") { type = NavType.StringType }),
        ) {
            val providerType = it.arguments!!.getString("providerType")!!
            ServerAddRoute(
                providerType = providerType,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.PROVIDER_EDIT,
            listOf(
                navArgument("providerType") { type = NavType.StringType },
                navArgument("sourceId") { type = NavType.StringType },
            ),
        ) {
            val providerType = it.arguments!!.getString("providerType")!!
            val sourceId = it.arguments!!.getString("sourceId")!!
            ServerEditRoute(
                providerType = providerType,
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
            val providerType = it.arguments!!.getString("provider")!!
            val sourceId = it.arguments!!.getString("sourceId")!!
            val location = it.arguments!!.getString("location")!!
            BrowseRoute(
                nav = nav,
                app = app,
                providerType = providerType,
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
            val providerType = it.arguments!!.getString("provider")!!
            val sourceId = it.arguments!!.getString("sourceId")!!
            val itemRef = it.arguments!!.getString("itemRef")!!
            DetailRoute(
                nav = nav,
                app = app,
                providerType = providerType,
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
            val providerType = it.arguments!!.getString("provider")!!
            val sourceId = it.arguments!!.getString("sourceId")!!
            val scope = it.arguments!!.getString("scopeLocation")!!
            SearchRoute(
                nav = nav,
                app = app,
                providerType = providerType,
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
            val providerType = it.arguments!!.getString("provider")!!
            val sourceId = it.arguments!!.getString("sourceId")!!
            val itemRef = it.arguments!!.getString("itemRef")!!
            val startMs = it.arguments!!.getLong("startMs")
            val itemRefDecoded = CatalogNav.decodeSegment(itemRef)
            PlayerRoute(
                app = app,
                providerType = providerType,
                sourceId = sourceId,
                itemRefDecoded = itemRefDecoded,
                startMs = startMs,
                onExit = { nav.popBackStack() },
            )
        }
    }
}
