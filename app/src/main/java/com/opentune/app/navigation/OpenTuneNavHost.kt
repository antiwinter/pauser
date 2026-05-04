package com.opentune.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opentune.app.OpenTuneApplication
import com.opentune.app.providers.OpenTuneProviderIds
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
    const val PROVIDER_ADD = "provider_add/{providerId}"
    const val PROVIDER_EDIT = "provider_edit/{providerId}/{sourceId}"

    fun providerAdd(providerId: String) = "provider_add/$providerId"

    fun providerEdit(providerId: String, sourceId: Long) = "provider_edit/$providerId/$sourceId"

    fun browse(providerId: String, sourceId: Long, locationRaw: String) =
        "browse/$providerId/$sourceId/${URLEncoder.encode(locationRaw, UrlCharset)}"

    fun detail(providerId: String, sourceId: Long, itemRefRaw: String) =
        "detail/$providerId/$sourceId/${URLEncoder.encode(itemRefRaw, UrlCharset)}"

    fun player(providerId: String, sourceId: Long, itemRefRaw: String, startMs: Long) =
        "player/$providerId/$sourceId/${URLEncoder.encode(itemRefRaw, UrlCharset)}/$startMs"

    fun search(providerId: String, sourceId: Long, scopeLocationRaw: String) =
        "search/$providerId/$sourceId/${URLEncoder.encode(scopeLocationRaw, UrlCharset)}"
}

@Composable
fun OpenTuneNavHost() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as OpenTuneApplication

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeRoute(
                database = app.database,
                onAddProvider = { pid -> nav.navigate(Routes.providerAdd(pid)) },
                onOpenBrowse = { pid, id, path ->
                    nav.navigate(Routes.browse(pid, id, path))
                },
                onEditProvider = { pid, id -> nav.navigate(Routes.providerEdit(pid, id)) },
            )
        }
        composable(
            Routes.PROVIDER_ADD,
            listOf(navArgument("providerId") { type = NavType.StringType }),
        ) {
            val providerId = it.arguments!!.getString("providerId")!!
            ServerAddRoute(
                providerId = providerId,
                database = app.database,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.PROVIDER_EDIT,
            listOf(
                navArgument("providerId") { type = NavType.StringType },
                navArgument("sourceId") { type = NavType.LongType },
            ),
        ) {
            val providerId = it.arguments!!.getString("providerId")!!
            val sourceId = it.arguments!!.getLong("sourceId")
            ServerEditRoute(
                providerId = providerId,
                database = app.database,
                sourceId = sourceId,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.BROWSE,
            listOf(
                navArgument("provider") { type = NavType.StringType },
                navArgument("sourceId") { type = NavType.LongType },
                navArgument("location") { type = NavType.StringType },
            ),
        ) {
            val providerId = it.arguments!!.getString("provider")!!
            check(OpenTuneProviderIds.isKnown(providerId)) { "Unknown provider: $providerId" }
            val sourceId = it.arguments!!.getLong("sourceId")
            val location = it.arguments!!.getString("location")!!
            BrowseRoute(
                nav = nav,
                app = app,
                database = app.database,
                providerId = providerId,
                sourceId = sourceId,
                locationEncoded = location,
            )
        }
        composable(
            Routes.DETAIL,
            listOf(
                navArgument("provider") { type = NavType.StringType },
                navArgument("sourceId") { type = NavType.LongType },
                navArgument("itemRef") { type = NavType.StringType },
            ),
        ) {
            val providerId = it.arguments!!.getString("provider")!!
            check(OpenTuneProviderIds.isKnown(providerId)) { "Unknown provider: $providerId" }
            val sourceId = it.arguments!!.getLong("sourceId")
            val itemRef = it.arguments!!.getString("itemRef")!!
            DetailRoute(
                nav = nav,
                app = app,
                database = app.database,
                providerId = providerId,
                sourceId = sourceId,
                itemRefEncoded = itemRef,
            )
        }
        composable(
            Routes.SEARCH,
            listOf(
                navArgument("provider") { type = NavType.StringType },
                navArgument("sourceId") { type = NavType.LongType },
                navArgument("scopeLocation") { type = NavType.StringType },
            ),
        ) {
            val providerId = it.arguments!!.getString("provider")!!
            check(OpenTuneProviderIds.isKnown(providerId)) { "Unknown provider: $providerId" }
            val sourceId = it.arguments!!.getLong("sourceId")
            val scope = it.arguments!!.getString("scopeLocation")!!
            SearchRoute(
                nav = nav,
                app = app,
                database = app.database,
                providerId = providerId,
                sourceId = sourceId,
                scopeLocationEncoded = scope,
            )
        }
        composable(
            Routes.PLAYER,
            listOf(
                navArgument("provider") { type = NavType.StringType },
                navArgument("sourceId") { type = NavType.LongType },
                navArgument("itemRef") { type = NavType.StringType },
                navArgument("startMs") { type = NavType.LongType },
            ),
        ) {
            val providerId = it.arguments!!.getString("provider")!!
            check(OpenTuneProviderIds.isKnown(providerId)) { "Unknown provider: $providerId" }
            val sourceId = it.arguments!!.getLong("sourceId")
            val itemRef = it.arguments!!.getString("itemRef")!!
            val startMs = it.arguments!!.getLong("startMs")
            val itemRefDecoded = CatalogNav.decodeSegment(itemRef)
            PlayerRoute(
                app = app,
                database = app.database,
                providerId = providerId,
                sourceId = sourceId,
                itemRefDecoded = itemRefDecoded,
                startMs = startMs,
                onExit = { nav.popBackStack() },
            )
        }
    }
}
