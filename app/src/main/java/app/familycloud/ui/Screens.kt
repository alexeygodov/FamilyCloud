package app.familycloud.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import app.familycloud.R
import app.familycloud.data.webdav.FileKind
import app.familycloud.data.webdav.RemoteEntry
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ConnectionScreen(viewModel: ConnectionViewModel, onConnected: () -> Unit) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    var url by remember(saved) { mutableStateOf(saved?.baseUrl ?: "https://") }
    var login by remember(saved) { mutableStateOf(saved?.login ?: "") }
    var password by remember(saved) { mutableStateOf(saved?.password ?: "") }
    var allowHttp by remember(saved) { mutableStateOf(saved?.allowHttp ?: false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.connection_url)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text(stringResource(R.string.connection_login)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.connection_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allowHttp, onCheckedChange = { allowHttp = it })
                Text(stringResource(R.string.connection_http))
            }
            Text(stringResource(R.string.connection_http_hint), style = MaterialTheme.typography.bodySmall)
            if (error != null) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { viewModel.submit(url, login, password, allowHttp, onConnected) },
                enabled = !busy && url.isNotBlank() && login.isNotBlank() && password.isNotEmpty(),
            ) {
                Text(
                    if (busy) stringResource(R.string.connection_checking) else stringResource(R.string.connection_test),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    picking: Boolean,
    onBack: (() -> Unit)?,
    onConnection: () -> Unit,
    onSync: () -> Unit,
    onConfirmFolder: (String) -> Unit,
    onChooseDestination: () -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
) {
    val path by viewModel.path.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var newFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var menuEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var renameEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.upload(uris)
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (path.isEmpty()) stringResource(R.string.files_root) else path.trim('/')) },
                navigationIcon = {
                    if (path.isNotEmpty() || onBack != null) {
                        IconButton(onClick = { if (path.isNotEmpty()) viewModel.up() else onBack?.invoke() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (picking) {
                        TextButton(onClick = { onConfirmFolder(path) }) { Text(stringResource(R.string.pick_here)) }
                    } else {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.refresh))
                        }
                        IconButton(onClick = { newFolder = true }) {
                            Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.new_folder))
                        }
                        IconButton(onClick = onSync) {
                            Icon(Icons.Filled.Sync, stringResource(R.string.sync_title))
                        }
                        IconButton(onClick = onConnection) {
                            Icon(Icons.Filled.Settings, stringResource(R.string.connection_title))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!picking) {
                FloatingActionButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.CloudUpload, stringResource(R.string.upload))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (entries.isEmpty() && !loading) {
                Text(
                    stringResource(R.string.empty_folder),
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(entries, key = { it.relativePath }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = { Text(details(entry)) },
                        leadingContent = {
                            Icon(
                                when {
                                    entry.isFolder -> Icons.Filled.Folder
                                    viewModel.kind(entry) == FileKind.Image -> Icons.Filled.Image
                                    viewModel.kind(entry) == FileKind.Video -> Icons.Filled.Movie
                                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                                },
                                contentDescription = null,
                            )
                        },
                        trailingContent = {
                            if (!picking) {
                                IconButton(onClick = { menuEntry = entry }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.isFolder) {
                                    val folder = if (entry.relativePath.endsWith("/")) entry.relativePath else "${entry.relativePath}/"
                                    viewModel.open(folder)
                                } else if (!picking) {
                                    val encoded = encodeNav(entry.relativePath)
                                    when (viewModel.kind(entry)) {
                                        FileKind.Image -> onOpenImage(encoded)
                                        FileKind.Video -> onOpenVideo(encoded)
                                        FileKind.Other -> viewModel.openCached(entry) { uri, mime ->
                                            val intent = Intent(Intent.ACTION_VIEW)
                                                .setDataAndType(uri, mime)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            try {
                                                context.startActivity(intent)
                                            } catch (_: android.content.ActivityNotFoundException) {
                                                viewModel.message.value = context.getString(R.string.error_open_external)
                                            }
                                        }
                                    }
                                }
                            },
                    )
                    if (menuEntry?.relativePath == entry.relativePath) {
                        DropdownMenu(expanded = true, onDismissRequest = { menuEntry = null }) {
                            if (!entry.isFolder && viewModel.kind(entry) != FileKind.Other) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.open)) },
                                    onClick = {
                                        menuEntry = null
                                        val encoded = encodeNav(entry.relativePath)
                                        if (viewModel.kind(entry) == FileKind.Image) onOpenImage(encoded) else onOpenVideo(encoded)
                                    },
                                )
                            }
                            if (!entry.isFolder && viewModel.kind(entry) == FileKind.Other) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.open)) },
                                    onClick = {
                                        menuEntry = null
                                        viewModel.openCached(entry) { uri, mime ->
                                            val intent = Intent(Intent.ACTION_VIEW)
                                                .setDataAndType(uri, mime)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            try {
                                                context.startActivity(intent)
                                            } catch (_: android.content.ActivityNotFoundException) {
                                                viewModel.message.value = context.getString(R.string.error_open_external)
                                            }
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.download)) },
                                onClick = {
                                    menuEntry = null
                                    viewModel.download(entry)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                onClick = {
                                    menuEntry = null
                                    renameEntry = entry
                                    renameText = entry.name
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.move)) },
                                onClick = {
                                    viewModel.pendingEntry = entry
                                    viewModel.pendingCopy = false
                                    menuEntry = null
                                    onChooseDestination()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy)) },
                                onClick = {
                                    viewModel.pendingEntry = entry
                                    viewModel.pendingCopy = true
                                    menuEntry = null
                                    onChooseDestination()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                onClick = {
                                    menuEntry = null
                                    deleteEntry = entry
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (newFolder) {
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text(stringResource(R.string.new_folder)) },
            text = {
                OutlinedTextField(value = folderName, onValueChange = { folderName = it }, label = { Text(stringResource(R.string.folder_name)) })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (folderName.isNotBlank()) viewModel.createFolder(folderName)
                    folderName = ""
                    newFolder = false
                }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val renaming = renameEntry
    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renameEntry = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text(stringResource(R.string.new_name)) })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) viewModel.rename(renaming, renameText)
                    renameEntry = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renameEntry = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val deleting = deleteEntry
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_confirm, deleting.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(deleting)
                    deleteEntry = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    viewModel: SyncViewModel,
    onBack: () -> Unit,
    onPickFolder: () -> Unit,
) {
    val rows by viewModel.rows.collectAsState()
    val albums by viewModel.albums.collectAsState()
    var showAlbums by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
                add(android.Manifest.permission.READ_MEDIA_VIDEO)
                if (Build.VERSION.SDK_INT >= 34) add(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            add(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.reload()
    }
    LaunchedEffect(Unit) { launcher.launch(permissions.toTypedArray()) }
    val partial = Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val power = context.getSystemService(android.os.PowerManager::class.java)
    val batteryOptimized = power?.isIgnoringBatteryOptimizations(context.packageName) == false

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { viewModel.syncNow() }) {
                        Icon(Icons.Filled.Sync, stringResource(R.string.sync_now))
                    }
                    IconButton(onClick = {
                        viewModel.loadAlbums()
                        showAlbums = true
                    }) {
                        Icon(Icons.Filled.Add, stringResource(R.string.sync_add))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (partial) {
                item { Text(stringResource(R.string.sync_partial), color = MaterialTheme.colorScheme.error) }
            }
            if (batteryOptimized) {
                item {
                    Column {
                        Text(stringResource(R.string.sync_battery))
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }) { Text(stringResource(R.string.sync_battery_action)) }
                    }
                }
            }
            if (rows.isEmpty()) {
                item { Text(stringResource(R.string.sync_empty)) }
            }
            items(rows, key = { it.pair.id }) { row ->
                val pair = row.pair
                Column(Modifier.fillMaxWidth()) {
                    Text(pair.albumName, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.sync_folder, pair.remoteFolder))
                    Text(stringResource(R.string.sync_pending, row.pending))
                    if (pair.lastError != null) {
                        Text(stringResource(R.string.sync_last_error, pair.lastError), color = MaterialTheme.colorScheme.error)
                    }
                    ToggleRow(stringResource(R.string.sync_enabled), pair.enabled) { viewModel.update(pair.copy(enabled = it)) }
                    ToggleRow(stringResource(R.string.sync_photos), pair.includePhotos) { viewModel.update(pair.copy(includePhotos = it)) }
                    ToggleRow(stringResource(R.string.sync_videos), pair.includeVideos) { viewModel.update(pair.copy(includeVideos = it)) }
                    ToggleRow(stringResource(R.string.sync_wifi), pair.wifiOnly) { viewModel.update(pair.copy(wifiOnly = it)) }
                    ToggleRow(stringResource(R.string.sync_charging), pair.chargingOnly) { viewModel.update(pair.copy(chargingOnly = it)) }
                    ToggleRow(stringResource(R.string.sync_only_new), pair.onlyNewSinceEpochMs != null) { viewModel.setOnlyNew(pair, it) }
                    TextButton(onClick = { viewModel.delete(pair.id) }) { Text(stringResource(R.string.sync_remove)) }
                }
            }
        }
    }

    if (showAlbums) {
        AlertDialog(
            onDismissRequest = { showAlbums = false },
            title = { Text(stringResource(R.string.sync_pick_album)) },
            text = {
                if (albums.isEmpty()) {
                    Text(stringResource(R.string.sync_no_albums))
                } else {
                    LazyColumn {
                        items(albums, key = { it.bucketId }) { album ->
                            TextButton(onClick = {
                                viewModel.pendingAlbum = album
                                showAlbums = false
                                onPickFolder()
                            }) { Text(album.name) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAlbums = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageScreen(viewModel: ImageViewModel, onBack: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Scaffold(topBar = { TopAppBar(title = { Text(viewModel.name) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
    }) }) { padding ->
        val url = viewModel.url
        if (url == null) {
            Text(stringResource(R.string.error_bad_path), modifier = Modifier.padding(padding).padding(24.dp))
        } else {
            AsyncImage(
                model = url,
                contentDescription = viewModel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset += pan
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(viewModel: VideoViewModel, onBack: () -> Unit) {
    val player = viewModel.player
    Scaffold(topBar = { TopAppBar(title = { Text(viewModel.name) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
    }) }) { padding ->
        if (player == null) {
            Text(stringResource(R.string.error_bad_path), modifier = Modifier.padding(padding).padding(24.dp))
        } else {
            AndroidView(
                factory = { context -> PlayerView(context).apply { this.player = player } },
                modifier = Modifier.fillMaxSize().padding(padding),
                update = { it.player = player },
            )
        }
    }
}

private fun details(entry: RemoteEntry): String {
    val size = entry.size?.let(::formatSize).orEmpty()
    val date = entry.modifiedEpochMs?.let { epoch ->
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))
    }.orEmpty()
    return listOf(size, date).filter { it.isNotEmpty() }.joinToString(" · ")
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val units = listOf("КБ", "МБ", "ГБ")
    var value = bytes.toDouble()
    var unit = "Б"
    for (candidate in units) {
        value /= 1024.0
        unit = candidate
        if (value < 1024) break
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, unit)
}
