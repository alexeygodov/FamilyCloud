package app.familycloud.ui.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familycloud.R
import coil3.compose.AsyncImage

@Composable
fun ImageRoute(
    viewModel: ImageViewModel,
    onBack: () -> Unit,
) {
    val images by viewModel.images.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    ImageScreen(
        path = viewModel.path,
        images = images,
        ready = ready,
        fileName = viewModel::fileName,
        urlOf = viewModel::urlOf,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageScreen(
    path: String,
    images: List<String>,
    ready: Boolean,
    fileName: (String) -> String,
    urlOf: (String) -> String?,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { images.size.coerceAtLeast(1) })
    var zoomed by remember { mutableStateOf(false) }
    LaunchedEffect(ready, images) {
        if (!ready) return@LaunchedEffect
        val index = images.indexOf(path).coerceAtLeast(0)
        pagerState.scrollToPage(index)
    }
    val current = images.getOrNull(pagerState.currentPage) ?: path
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName(current)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (current.isEmpty()) {
            Text(stringResource(R.string.error_bad_path), modifier = Modifier.padding(padding).padding(24.dp))
        } else {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) { page ->
                val item = images.getOrNull(page) ?: return@HorizontalPager
                val url = urlOf(item)
                if (url == null) {
                    Text(stringResource(R.string.error_bad_path), modifier = Modifier.padding(24.dp))
                } else {
                    ZoomableImage(url, fileName(item)) { zoomedNow ->
                        if (page == pagerState.currentPage) zoomed = zoomedNow
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(url: String, name: String, onZoomed: (Boolean) -> Unit) {
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxX = (constraints.maxWidth * (scale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight * (scale - 1f) / 2f).coerceAtLeast(0f)
        AsyncImage(
            model = url,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(url) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                            onZoomed(false)
                        } else {
                            scale = 2.5f
                            onZoomed(true)
                        }
                    })
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
                        onZoomed(next > 1f)
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x.coerceIn(-maxX, maxX)
                    translationY = offset.y.coerceIn(-maxY, maxY)
                },
        )
    }
}
