package app.familycloud.ui.viewer

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familycloud.R
import app.familycloud.ui.formatByteSize
import app.familycloud.ui.formatEpoch
import app.familycloud.ui.theme.Dimens
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import kotlin.math.abs

@Composable
fun ImageRoute(
    viewModel: ImageViewModel,
    onBack: () -> Unit,
) {
    val images by viewModel.images.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    ImageScreen(
        path = viewModel.path,
        images = images,
        ready = ready,
        message = message,
        fileName = viewModel::fileName,
        urlOf = viewModel::urlOf,
        onBack = onBack,
        onDownload = viewModel::download,
        onDelete = { viewModel.delete(it, onBack) },
        onMessageShown = viewModel::consumeMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageScreen(
    path: String,
    images: List<ViewerPhoto>,
    ready: Boolean,
    message: String?,
    fileName: (String) -> String,
    urlOf: (String) -> String?,
    onBack: () -> Unit,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMessageShown: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { images.size.coerceAtLeast(1) })
    var zoomed by remember { mutableStateOf(false) }
    var atEdge by remember { mutableStateOf(true) }
    var chrome by remember { mutableStateOf(true) }
    var details by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ready) {
        if (!ready || images.isEmpty()) return@LaunchedEffect
        val index = images.indexOfFirst { it.path == path }.coerceAtLeast(0)
        pagerState.scrollToPage(index.coerceAtMost(images.lastIndex))
    }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        onMessageShown()
    }
    val current = images.getOrNull(pagerState.currentPage) ?: images.firstOrNull()
    ViewerSystemBars(chrome)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (current == null || current.path.isEmpty()) {
            Text(stringResource(R.string.error_bad_path), color = Color.White, modifier = Modifier.padding(Dimens.xl))
        } else {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !zoomed || atEdge,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = images.getOrNull(page) ?: return@HorizontalPager
                val url = urlOf(item.path)
                if (url == null) {
                    Text(stringResource(R.string.error_bad_path), color = Color.White, modifier = Modifier.padding(Dimens.xl))
                } else {
                    ZoomableImage(url, fileName(item.path), onTap = { chrome = !chrome }) { zoomedNow, edge ->
                        if (page == pagerState.currentPage) {
                            zoomed = zoomedNow
                            atEdge = edge
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = chrome, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            ViewerChrome(
                title = current?.let { fileName(it.path) }.orEmpty(),
                index = pagerState.currentPage + 1,
                total = images.size,
                onBack = onBack,
                onDownload = { current?.let { onDownload(it.path) } },
                onDetails = { details = true },
                onDelete = { confirmDelete = true },
            )
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
    if (details && current != null) {
        ModalBottomSheet(onDismissRequest = { details = false }) {
            Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Text(stringResource(R.string.viewer_details), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.viewer_size, formatByteSize(current.size)))
                Text(stringResource(R.string.viewer_date, current.modified?.let { formatEpoch(it) } ?: stringResource(R.string.sync_never)))
                Text(stringResource(R.string.viewer_path, current.path))
            }
        }
    }
    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title_one, fileName(current.path))) },
            text = { Text(stringResource(R.string.delete_irreversible)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(current.path)
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ViewerSystemBars(chrome: Boolean) {
    val view = LocalView.current
    DisposableEffect(chrome) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, view)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            if (chrome) controller.show(WindowInsetsCompat.Type.systemBars())
            else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            (view.context as? Activity)?.window?.let { window ->
                WindowInsetsControllerCompat(window, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
private fun ViewerChrome(
    title: String,
    index: Int,
    total: Int,
    onBack: () -> Unit,
    onDownload: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    val scrim = Color.Black.copy(alpha = 0.5f)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(
            Modifier.fillMaxWidth().background(scrim).padding(horizontal = Dimens.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (total > 0) {
                Text(stringResource(R.string.viewer_count, index, total), color = Color.White)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(scrim),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, stringResource(R.string.download), tint = Color.White)
            }
            IconButton(onClick = onDetails) {
                Icon(Icons.Filled.Info, stringResource(R.string.viewer_details), tint = Color.White)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = Color.White)
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    url: String,
    name: String,
    onTap: () -> Unit,
    onZoomed: (Boolean, Boolean) -> Unit,
) {
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxX = (constraints.maxWidth * (scale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight * (scale - 1f) / 2f).coerceAtLeast(0f)
        val context = LocalContext.current
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context).data(url).memoryCacheKey("$url#$attempt").build(),
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(url) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                                onZoomed(false, true)
                            } else {
                                scale = 2.5f
                                onZoomed(true, false)
                            }
                        },
                    )
                }
                .pointerInput(url) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 5f)
                        val limitX = (constraints.maxWidth * (next - 1f) / 2f).coerceAtLeast(0f)
                        val limitY = (constraints.maxHeight * (next - 1f) / 2f).coerceAtLeast(0f)
                        scale = next
                        offset = if (next == 1f) {
                            Offset.Zero
                        } else {
                            Offset(
                                (offset.x + pan.x).coerceIn(-limitX, limitX),
                                (offset.y + pan.y).coerceIn(-limitY, limitY),
                            )
                        }
                        val edge = next == 1f || abs(offset.x) >= limitX - 1f
                        onZoomed(next > 1f, edge)
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x.coerceIn(-maxX, maxX)
                    translationY = offset.y.coerceIn(-maxY, maxY)
                },
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            },
            error = {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = Color.White)
                    TextButton(onClick = { attempt += 1 }) { Text(stringResource(R.string.retry)) }
                }
            },
        )
    }
}
