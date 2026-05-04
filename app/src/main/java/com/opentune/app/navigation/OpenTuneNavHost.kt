package com.opentune.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opentune.app.OpenTuneApplication
import com.opentune.app.ui.emby.EmbyAddRoute
import com.opentune.app.ui.emby.EmbyBrowseRoute
import com.opentune.app.ui.emby.EmbyDetailRoute
import com.opentune.app.ui.emby.EmbyEditRoute
import com.opentune.app.ui.emby.EmbyLibrariesRoute
import com.opentune.app.ui.emby.EmbyPlayerRoute
import com.opentune.app.ui.home.HomeRoute
import com.opentune.app.ui.smb.SmbAddRoute
import com.opentune.app.ui.smb.SmbBrowseRoute
import com.opentune.app.ui.smb.SmbEditRoute
import com.opentune.app.ui.smb.SmbPlayerRoute
import java.net.URLEncoder

object Routes {

    /** [URLEncoder.encode] with Charset is API 33+; use charset name for older Android TV devices. */
    private const val UrlCharset = "UTF-8"
    const val HOME = "home"
    const val EMBY_ADD = "emby_add"
    const val EMBY_EDIT = "emby_edit/{serverId}"
    const val EMBY_LIBRARIES = "emby_libraries/{serverId}"
    const val EMBY_BROWSE = "emby_browse/{serverId}/{parentId}"
    const val EMBY_DETAIL = "emby_detail/{serverId}/{itemId}"
    const val EMBY_PLAYER = "emby_player/{serverId}/{itemId}/{startMs}"
    const val SMB_ADD = "smb_add"
    const val SMB_EDIT = "smb_edit/{sourceId}"
    const val SMB_BROWSE = "smb_browse/{sourceId}/{path}"
    const val SMB_PLAYER = "smb_player/{sourceId}/{path}"

    fun embyLibraries(serverId: Long) = "emby_libraries/$serverId"

    fun embyBrowse(serverId: Long, parentId: String) =
        "emby_browse/$serverId/${URLEncoder.encode(parentId, UrlCharset)}"

    fun embyDetail(serverId: Long, itemId: String) =
        "emby_detail/$serverId/${URLEncoder.encode(itemId, UrlCharset)}"

    fun embyPlayer(serverId: Long, itemId: String, startMs: Long) =
        "emby_player/$serverId/${URLEncoder.encode(itemId, UrlCharset)}/$startMs"

    fun embyEdit(serverId: Long) = "emby_edit/$serverId"

    fun smbBrowse(sourceId: Long, path: String) =
        "smb_browse/$sourceId/${URLEncoder.encode(path, UrlCharset)}"

    fun smbPlayer(sourceId: Long, path: String) =
        "smb_player/$sourceId/${URLEncoder.encode(path, UrlCharset)}"

    fun smbEdit(sourceId: Long) = "smb_edit/$sourceId"
}

@Composable
fun OpenTuneNavHost() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as OpenTuneApplication

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeRoute(
                database = app.database,
                onAddEmby = { nav.navigate(Routes.EMBY_ADD) },
                onOpenServer = { id -> nav.navigate(Routes.embyLibraries(id)) },
                onEditEmby = { id -> nav.navigate(Routes.embyEdit(id)) },
                onAddSmb = { nav.navigate(Routes.SMB_ADD) },
                onOpenSmb = { sid, path -> nav.navigate(Routes.smbBrowse(sid, path)) },
                onEditSmb = { id -> nav.navigate(Routes.smbEdit(id)) },
            )
        }
        composable(Routes.EMBY_ADD) {
            EmbyAddRoute(
                database = app.database,
                deviceProfile = app.deviceProfile,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.EMBY_EDIT,
            listOf(navArgument("serverId") { type = NavType.LongType }),
        ) {
            val serverId = it.arguments!!.getLong("serverId")
            EmbyEditRoute(
                database = app.database,
                serverId = serverId,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.EMBY_LIBRARIES,
            listOf(navArgument("serverId") { type = NavType.LongType }),
        ) {
            val serverId = it.arguments!!.getLong("serverId")
            EmbyLibrariesRoute(
                app = app,
                serverId = serverId,
                onBack = { nav.popBackStack() },
                onOpenLibrary = { pid -> nav.navigate(Routes.embyBrowse(serverId, pid)) },
            )
        }
        composable(
            Routes.EMBY_BROWSE,
            listOf(
                navArgument("serverId") { type = NavType.LongType },
                navArgument("parentId") { type = NavType.StringType },
            ),
        ) {
            val serverId = it.arguments!!.getLong("serverId")
            val parentId = it.arguments!!.getString("parentId")!!
            EmbyBrowseRoute(
                app = app,
                serverId = serverId,
                parentId = parentId,
                onBack = { nav.popBackStack() },
                onOpenFolder = { childId -> nav.navigate(Routes.embyBrowse(serverId, childId)) },
                onOpenDetail = { itemId -> nav.navigate(Routes.embyDetail(serverId, itemId)) },
            )
        }
        composable(
            Routes.EMBY_DETAIL,
            listOf(
                navArgument("serverId") { type = NavType.LongType },
                navArgument("itemId") { type = NavType.StringType },
            ),
        ) {
            val serverId = it.arguments!!.getLong("serverId")
            val itemId = it.arguments!!.getString("itemId")!!
            EmbyDetailRoute(
                app = app,
                serverId = serverId,
                itemId = itemId,
                onBack = { nav.popBackStack() },
                onPlay = { startMs -> nav.navigate(Routes.embyPlayer(serverId, itemId, startMs)) },
            )
        }
        composable(
            Routes.EMBY_PLAYER,
            listOf(
                navArgument("serverId") { type = NavType.LongType },
                navArgument("itemId") { type = NavType.StringType },
                navArgument("startMs") { type = NavType.LongType },
            ),
        ) {
            val serverId = it.arguments!!.getLong("serverId")
            val itemId = it.arguments!!.getString("itemId")!!
            val startMs = it.arguments!!.getLong("startMs")
            EmbyPlayerRoute(
                app = app,
                serverId = serverId,
                itemId = itemId,
                startPositionMs = startMs,
                onExit = { nav.popBackStack() },
            )
        }
        composable(Routes.SMB_ADD) {
            SmbAddRoute(
                database = app.database,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.SMB_EDIT,
            listOf(navArgument("sourceId") { type = NavType.LongType }),
        ) {
            val sourceId = it.arguments!!.getLong("sourceId")
            SmbEditRoute(
                database = app.database,
                sourceId = sourceId,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.SMB_BROWSE,
            listOf(
                navArgument("sourceId") { type = NavType.LongType },
                navArgument("path") { type = NavType.StringType },
            ),
        ) {
            val sourceId = it.arguments!!.getLong("sourceId")
            val path = it.arguments!!.getString("path")!!
            SmbBrowseRoute(
                database = app.database,
                sourceId = sourceId,
                initialPath = path,
                onBack = { nav.popBackStack() },
                onPlayVideo = { filePath ->
                    nav.navigate(Routes.smbPlayer(sourceId, filePath))
                },
            )
        }
        composable(
            Routes.SMB_PLAYER,
            listOf(
                navArgument("sourceId") { type = NavType.LongType },
                navArgument("path") { type = NavType.StringType },
            ),
        ) {
            val sourceId = it.arguments!!.getLong("sourceId")
            val path = it.arguments!!.getString("path")!!
            SmbPlayerRoute(
                database = app.database,
                sourceId = sourceId,
                filePath = path,
                onExit = { nav.popBackStack() },
            )
        }
    }
}
