package com.opentune.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opentune.app.OpenTuneApplication
import com.opentune.app.ui.emby.AddEmbyRoute
import com.opentune.app.ui.emby.EditEmbyRoute
import com.opentune.app.ui.emby.BrowseRoute
import com.opentune.app.ui.emby.DetailRoute
import com.opentune.app.ui.emby.LibrariesRoute
import com.opentune.app.ui.home.HomeRoute
import com.opentune.app.ui.player.PlayerRoute
import com.opentune.app.ui.smb.AddSmbRoute
import com.opentune.app.ui.smb.EditSmbRoute
import com.opentune.app.ui.smb.SmbBrowseRoute
import java.net.URLEncoder

object Routes {

    /** [URLEncoder.encode] with Charset is API 33+; use charset name for older Android TV devices. */
    private const val UrlCharset = "UTF-8"
    const val HOME = "home"
    const val ADD_EMBY = "add_emby"
    const val EDIT_EMBY = "edit_emby/{serverId}"
    const val EDIT_SMB = "edit_smb/{sourceId}"
    const val LIBRARIES = "libraries/{serverId}"
    const val BROWSE = "browse/{serverId}/{parentId}"
    const val DETAIL = "detail/{serverId}/{itemId}"
    const val PLAYER = "player/{serverId}/{itemId}/{startMs}"
    const val ADD_SMB = "add_smb"
    const val SMB_BROWSE = "smb_browse/{sourceId}/{path}"

    fun libraries(serverId: Long) = "libraries/$serverId"

    fun browse(serverId: Long, parentId: String) =
        "browse/$serverId/${URLEncoder.encode(parentId, UrlCharset)}"

    fun detail(serverId: Long, itemId: String) =
        "detail/$serverId/${URLEncoder.encode(itemId, UrlCharset)}"

    fun player(serverId: Long, itemId: String, startMs: Long) =
        "player/$serverId/${URLEncoder.encode(itemId, UrlCharset)}/$startMs"

    fun smbBrowse(sourceId: Long, path: String) =
        "smb_browse/$sourceId/${URLEncoder.encode(path, UrlCharset)}"

    fun editEmby(serverId: Long) = "edit_emby/$serverId"

    fun editSmb(sourceId: Long) = "edit_smb/$sourceId"
}

@Composable
fun OpenTuneNavHost() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as OpenTuneApplication

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeRoute(
                database = app.database,
                onAddEmby = { nav.navigate(Routes.ADD_EMBY) },
                onOpenServer = { id -> nav.navigate(Routes.libraries(id)) },
                onEditEmby = { id -> nav.navigate(Routes.editEmby(id)) },
                onAddSmb = { nav.navigate(Routes.ADD_SMB) },
                onOpenSmb = { sid, path -> nav.navigate(Routes.smbBrowse(sid, path)) },
                onEditSmb = { id -> nav.navigate(Routes.editSmb(id)) },
            )
        }
        composable(Routes.ADD_EMBY) {
            AddEmbyRoute(
                database = app.database,
                deviceProfile = app.deviceProfile,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.EDIT_EMBY,
            listOf(navArgument("serverId") { type = NavType.LongType }),
        ) {
            val serverId = it.arguments!!.getLong("serverId")
            EditEmbyRoute(
                database = app.database,
                serverId = serverId,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.LIBRARIES,
            listOf(navArgument("serverId") { type = NavType.LongType }),
        ) {
            val serverId = it.arguments!!.getLong("serverId")
            LibrariesRoute(
                app = app,
                serverId = serverId,
                onBack = { nav.popBackStack() },
                onOpenLibrary = { pid -> nav.navigate(Routes.browse(serverId, pid)) },
            )
        }
        composable(
            Routes.BROWSE,
            listOf(
                navArgument("serverId") { type = NavType.LongType },
                navArgument("parentId") { type = NavType.StringType },
            ),
        ) {
            val serverId = it.arguments!!.getLong("serverId")
            val parentId = it.arguments!!.getString("parentId")!!
            BrowseRoute(
                app = app,
                serverId = serverId,
                parentId = parentId,
                onBack = { nav.popBackStack() },
                onOpenFolder = { childId -> nav.navigate(Routes.browse(serverId, childId)) },
                onOpenDetail = { itemId -> nav.navigate(Routes.detail(serverId, itemId)) },
            )
        }
        composable(
            Routes.DETAIL,
            listOf(
                navArgument("serverId") { type = NavType.LongType },
                navArgument("itemId") { type = NavType.StringType },
            ),
        ) {
            val serverId = it.arguments!!.getLong("serverId")
            val itemId = it.arguments!!.getString("itemId")!!
            DetailRoute(
                app = app,
                serverId = serverId,
                itemId = itemId,
                onBack = { nav.popBackStack() },
                onPlay = { startMs -> nav.navigate(Routes.player(serverId, itemId, startMs)) },
            )
        }
        composable(
            Routes.PLAYER,
            listOf(
                navArgument("serverId") { type = NavType.LongType },
                navArgument("itemId") { type = NavType.StringType },
                navArgument("startMs") { type = NavType.LongType },
            ),
        ) {
            val serverId = it.arguments!!.getLong("serverId")
            val itemId = it.arguments!!.getString("itemId")!!
            val startMs = it.arguments!!.getLong("startMs")
            PlayerRoute(
                app = app,
                serverId = serverId,
                itemId = itemId,
                startPositionMs = startMs,
                onExit = { nav.popBackStack() },
            )
        }
        composable(Routes.ADD_SMB) {
            AddSmbRoute(
                database = app.database,
                onDone = { nav.popBackStack() },
            )
        }
        composable(
            Routes.EDIT_SMB,
            listOf(navArgument("sourceId") { type = NavType.LongType }),
        ) {
            val sourceId = it.arguments!!.getLong("sourceId")
            EditSmbRoute(
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
            )
        }
    }
}
