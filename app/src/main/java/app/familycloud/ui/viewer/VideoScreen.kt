package app.familycloud.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.familycloud.R
import app.familycloud.ui.theme.Dimens

@Composable
fun VideoRoute(
    viewModel: VideoViewModel,
    onBack: () -> Unit,
) {
    val message by viewModel.message.collectAsStateWithLifecycle()
    VideoScreen(
        name = viewModel.name,
        player = viewModel.player,
        message = message,
        onBack = onBack,
        onRetry = viewModel::retry,
        onDownload = viewModel::download,
        onMessageShown = viewModel::consumeMessage,
    )
}

@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(
    name: String,
    player: ExoPlayer?,
    message: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var chrome by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        onMessageShown()
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player == null) {
            Text(stringResource(R.string.error_bad_path), color = Color.White, modifier = Modifier.padding(Dimens.xl))
        } else {
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, player) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) player.pause()
                }
                val errors = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        failed = true
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                player.addListener(errors)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    player.removeListener(errors)
                }
            }
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        this.player = player
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                            chrome = visibility == View.VISIBLE
                        })
                        setFullscreenButtonClickListener { full ->
                            context.findActivity()?.requestedOrientation = if (full) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { it.player = player },
            )
            if (failed) {
                Column(
                    Modifier.align(Alignment.Center).padding(Dimens.l),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.s),
                ) {
                    Text(stringResource(R.string.viewer_video_error), color = Color.White)
                    Row {
                        TextButton(onClick = {
                            failed = false
                            onRetry()
                        }) { Text(stringResource(R.string.retry)) }
                        TextButton(onClick = onDownload) { Text(stringResource(R.string.download)) }
                    }
                }
            }
        }
        if (chrome) {
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(Dimens.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                }
                Text(name, color = Color.White, modifier = Modifier.weight(1f))
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
