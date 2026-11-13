package app.familycloud.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.familycloud.FamilyCloudApp
import app.familycloud.R
import app.familycloud.ui.browser.BrowserRoute
import app.familycloud.ui.browser.BrowserViewModel
import app.familycloud.ui.browser.FolderPickerRoute
import app.familycloud.ui.browser.parsePickMode
import app.familycloud.ui.settings.ConnectionRoute
import app.familycloud.ui.settings.ConnectionViewModel
import app.familycloud.ui.sync.SyncRoute
import app.familycloud.ui.sync.SyncViewModel
import app.familycloud.ui.viewer.ImageRoute
import app.familycloud.ui.viewer.ImageViewModel
import app.familycloud.ui.viewer.VideoRoute
import app.familycloud.ui.viewer.VideoViewModel

@Composable
fun FamilyCloudNav(app: FamilyCloudApp, open: String? = null) {
    val factory = remember { AppViewModelFactory(app) }
    val nav = rememberNavController()
    LaunchedEffect(open) {
        val route = open ?: return@LaunchedEffect
        snapshotFlow { nav.currentDestination?.route }
            .filterNotNull()
            .first { it != "boot" }
        if (route == "sync" && nav.currentDestination?.route != "sync") nav.navigate("sync")
    }
    NavHost(navController = nav, startDestination = "boot") {
        composable("boot") {
            BootScreen(app) { destination ->
                nav.navigate(destination) { popUpTo("boot") { inclusive = true } }
            }
        }
        composable(
            route = "connection?standalone={standalone}",
            arguments = listOf(
                navArgument("standalone") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val model: ConnectionViewModel = viewModel(factory = factory)
            val standalone = entry.arguments?.getBoolean("standalone") == true
            ConnectionRoute(
                viewModel = model,
                showBack = !standalone,
                onBack = { nav.popBackStack() },
                onConnected = {
                    if (standalone) {
                        nav.navigate("browser") {
                            popUpTo(entry.destination.id) { inclusive = true }
                        }
                        nav.getBackStackEntry("browser").savedStateHandle["justConnected"] = true
                    } else {
                        nav.previousBackStackEntry?.savedStateHandle?.set("refreshAfterConnect", true)
                        nav.previousBackStackEntry?.savedStateHandle?.set("justConnected", true)
                        nav.popBackStack()
                    }
                },
            )
        }
        composable("browser") { entry ->
            val model: BrowserViewModel = viewModel(factory = factory)
            LaunchedEffect(entry) {
                entry.savedStateHandle.getStateFlow<String?>("pickedFolder", null).collect { folder ->
                    if (folder != null) {
                        entry.savedStateHandle["pickedFolder"] = null
                        model.onFolderPicked(folder)
                    }
                }
            }
            LaunchedEffect(entry) {
                entry.savedStateHandle.getStateFlow("refreshAfterConnect", false).collect { refresh ->
                    if (refresh) {
                        entry.savedStateHandle["refreshAfterConnect"] = false
                        model.refresh()
                    }
                }
            }
            LaunchedEffect(entry) {
                entry.savedStateHandle.getStateFlow("justConnected", false).collect { connected ->
                    if (connected) {
                        entry.savedStateHandle["justConnected"] = false
                        model.notifyConnected()
                    }
                }
            }
            BrowserRoute(
                viewModel = model,
                onConnection = { nav.navigate("connection?standalone=false") },
                onSync = { nav.navigate("sync") },
                onChooseDestination = {
                    val mode = if (model.pendingCopy) "copy" else "move"
                    val from = encodeNav(model.path.value)
                    val blocked = encodeNav(
                        model.pendingEntries.filter { it.isFolder }.joinToString("\n") { it.relativePath },
                    )
                    nav.navigate("picker?mode=$mode&from=$from&blocked=$blocked&album=")
                },
                onOpenImage = { encoded ->
                    val order = model.sort.value
                    nav.navigate("image/$encoded?field=${order.field.name}&asc=${order.ascending}")
                },
                onOpenVideo = { encoded -> nav.navigate("video/$encoded") },
            )
        }
        composable(
            route = "picker?mode={mode}&album={album}&from={from}&blocked={blocked}",
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType; defaultValue = "move" },
                navArgument("album") { type = NavType.StringType; defaultValue = "" },
                navArgument("from") { type = NavType.StringType; defaultValue = "" },
                navArgument("blocked") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val model: BrowserViewModel = viewModel(factory = factory)
            val album = decodeNav(entry.arguments?.getString("album").orEmpty())
            val source = decodeNav(entry.arguments?.getString("from").orEmpty())
            val blockedRaw = decodeNav(entry.arguments?.getString("blocked").orEmpty())
            val blocked = if (blockedRaw.isEmpty()) emptyList() else blockedRaw.split('\n')
            FolderPickerRoute(
                viewModel = model,
                mode = parsePickMode(entry.arguments?.getString("mode").orEmpty()),
                albumName = album,
                source = source,
                blocked = blocked,
                onCancel = { nav.popBackStack() },
                onConfirm = { folder ->
                    nav.previousBackStackEntry?.savedStateHandle?.set("pickedFolder", folder)
                    nav.popBackStack()
                },
            )
        }
        composable("sync") { entry ->
            val model: SyncViewModel = viewModel(factory = factory)
            LaunchedEffect(entry) {
                entry.savedStateHandle.getStateFlow<String?>("pickedFolder", null).collect { folder ->
                    if (folder != null) {
                        entry.savedStateHandle["pickedFolder"] = null
                        model.onFolderPicked(folder)
                    }
                }
            }
            SyncRoute(
                viewModel = model,
                onBack = { nav.popBackStack() },
                onPickFolder = {
                    val album = encodeNav(model.pickLabel())
                    nav.navigate("picker?mode=album&album=$album&from=&blocked=")
                },
            )
        }
        composable(
            route = "image/{path}?field={field}&asc={asc}",
            arguments = listOf(
                navArgument("field") { type = NavType.StringType; defaultValue = "Name" },
                navArgument("asc") { type = NavType.StringType; defaultValue = "true" },
            ),
        ) {
            val model: ImageViewModel = viewModel(factory = factory)
            ImageRoute(model) { nav.popBackStack() }
        }
        composable("video/{path}") {
            val model: VideoViewModel = viewModel(factory = factory)
            VideoRoute(model) { nav.popBackStack() }
        }
    }
}

@Composable
private fun BootScreen(app: FamilyCloudApp, onReady: (String) -> Unit) {
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        app.container.settings.load()
        loaded = true
    }
    LaunchedEffect(loaded) {
        if (loaded) {
            val destination = if (app.container.settings.snapshot() == null) {
                "connection?standalone=true"
            } else {
                "browser"
            }
            onReady(destination)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.app_name))
    }
}
