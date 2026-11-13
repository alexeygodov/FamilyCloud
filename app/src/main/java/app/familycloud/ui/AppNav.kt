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

@Composable
fun FamilyCloudNav(app: FamilyCloudApp) {
    val factory = remember { AppViewModelFactory(app) }
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "boot") {
        composable("boot") {
            BootScreen(app) { destination ->
                nav.navigate(destination) { popUpTo("boot") { inclusive = true } }
            }
        }
        composable("connection") {
            val model: ConnectionViewModel = viewModel(factory = factory)
            ConnectionScreen(model) {
                nav.navigate("browser") { popUpTo("connection") { inclusive = true } }
            }
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
            BrowserScreen(
                viewModel = model,
                picking = false,
                onBack = null,
                onConnection = { nav.navigate("connection") },
                onSync = { nav.navigate("sync") },
                onConfirmFolder = {},
                onChooseDestination = { nav.navigate("picker") },
                onOpenImage = { encoded ->
                    val order = model.sort.value
                    nav.navigate("image/$encoded?field=${order.field.name}&asc=${order.ascending}")
                },
                onOpenVideo = { encoded -> nav.navigate("video/$encoded") },
            )
        }
        composable("picker") {
            val model: BrowserViewModel = viewModel(factory = factory)
            BrowserScreen(
                viewModel = model,
                picking = true,
                onBack = { nav.popBackStack() },
                onConnection = {},
                onSync = {},
                onConfirmFolder = { folder ->
                    nav.previousBackStackEntry?.savedStateHandle?.set("pickedFolder", folder)
                    nav.popBackStack()
                },
                onChooseDestination = {},
                onOpenImage = {},
                onOpenVideo = {},
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
            SyncScreen(
                viewModel = model,
                onBack = { nav.popBackStack() },
                onPickFolder = { nav.navigate("picker") },
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
            ImageScreen(model) { nav.popBackStack() }
        }
        composable("video/{path}") {
            val model: VideoViewModel = viewModel(factory = factory)
            VideoScreen(model) { nav.popBackStack() }
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
            val destination = if (app.container.settings.snapshot() == null) "connection" else "browser"
            onReady(destination)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.app_name))
    }
}
