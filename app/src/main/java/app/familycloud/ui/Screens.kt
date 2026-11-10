package app.familycloud.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import app.familycloud.R
import app.familycloud.data.webdav.FileKind
import app.familycloud.data.webdav.RemoteEntry
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ConnectionScreen(viewModel: ConnectionViewModel, onConnected: () -> Unit) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val initial = remember { saved }
    var url by rememberSaveable { mutableStateOf(initial?.baseUrl ?: "https://") }
    var login by rememberSaveable { mutableStateOf(initial?.login ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var allowHttp by rememberSaveable { mutableStateOf(initial?.allowHttp ?: false) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text(stringResource(R.string.connection_login)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.connection_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                    }
                },
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
    val transferring by viewModel.transferring.collectAsState()
    val message by viewModel.message.collectAsState()
    val failure by viewModel.failure.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var menuEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var folderName by rememberSaveable { mutableStateOf("") }
    var renamePath by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deletePath by rememberSaveable { mutableStateOf<String?>(null) }
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
                    IconButton(onClick = { newFolder = true }) {
                        Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.new_folder))
                    }
                    if (picking) {
                        TextButton(onClick = { onConfirmFolder(path) }) { Text(stringResource(R.string.pick_here)) }
                    } else {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.refresh))
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
            if (transferring) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failure != null) {
                Column(Modifier.padding(16.dp)) {
                    Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { viewModel.refresh() }) { Text(stringResource(R.string.retry)) }
                }
            }
            if (entries.isEmpty() && !loading && failure == null) {
                Text(
                    stringResource(R.string.empty_folder),
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(entries, key = { it.relativePath }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.label) },
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
                                        FileKind.Other -> viewModel.openExternally(entry) { uri, mime ->
                                            tryView(context, uri, mime)
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
                                        viewModel.openExternally(entry) { uri, mime ->
                                            tryView(context, uri, mime)
                                        }
                                    },
                                )
                            }
                            if (!entry.isFolder) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.download)) },
                                    onClick = {
                                        menuEntry = null
                                        viewModel.download(entry)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                onClick = {
                                    menuEntry = null
                                    renamePath = entry.relativePath
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
                                    deletePath = entry.relativePath
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
    val renaming = entries.firstOrNull { it.relativePath == renamePath }
    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renamePath = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text(stringResource(R.string.new_name)) })
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) viewModel.rename(renaming, renameText)
                    renamePath = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renamePath = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val deleting = entries.firstOrNull { it.relativePath == deletePath }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deletePath = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_confirm, deleting.label)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(deleting)
                    deletePath = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deletePath = null }) { Text(stringResource(R.string.cancel)) } },
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
        viewModel.loadAlbums()
        showAlbums = true
    }
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
                        val images = if (Build.VERSION.SDK_INT >= 33) {
                            android.Manifest.permission.READ_MEDIA_IMAGES
                        } else {
                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                        }
                        val allowed = ContextCompat.checkSelfPermission(context, images) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED ||
                            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                        if (allowed) {
                            viewModel.loadAlbums()
                            showAlbums = true
                        } else {
                            launcher.launch(permissions.toTypedArray())
                        }
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
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_MEDIA_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                item { Text(stringResource(R.string.sync_location)) }
            }
            val imagesGranted = if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            } else {
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (!imagesGranted) {
                item {
                    Column {
                        Text(stringResource(R.string.sync_permission))
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }) { Text(stringResource(R.string.open_settings)) }
                    }
                }
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
                    if (pair.lastSyncEpochMs != null) {
                        Text(stringResource(R.string.sync_last, formatDate(pair.lastSyncEpochMs)))
                    }
                    Text(stringResource(R.string.sync_pending, row.pending))
                    if (pair.lastError != null) {
                        Text(failureLabel(pair.lastError), color = MaterialTheme.colorScheme.error)
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
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, player) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) player.pause()
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            AndroidView(
                factory = { context -> PlayerView(context).apply { this.player = player } },
                modifier = Modifier.fillMaxSize().padding(padding),
                update = { it.player = player },
            )
        }
    }
}

@Composable
private fun formatDate(epochMs: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", locale)
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}

@Composable
private fun failureLabel(code: String): String {
    val http = code.removePrefix("HTTP_")
    val text = when (code) {
        "NETWORK" -> stringResource(R.string.error_network)
        "TIMEOUT" -> stringResource(R.string.error_timeout)
        "SSL" -> stringResource(R.string.error_ssl)
        "HTTP_DISABLED" -> stringResource(R.string.error_http_off)
        "UNAUTHORIZED" -> stringResource(R.string.error_unauthorized)
        "FORBIDDEN" -> stringResource(R.string.error_forbidden)
        "NOT_FOUND" -> stringResource(R.string.error_not_found)
        "METHOD" -> stringResource(R.string.error_method)
        "CONFLICT" -> stringResource(R.string.error_conflict)
        "LOCKED" -> stringResource(R.string.error_locked)
        "STORAGE" -> stringResource(R.string.error_storage)
        "BAD_URL" -> stringResource(R.string.error_url)
        "NO_CONNECTION" -> stringResource(R.string.error_no_connection)
        "MEDIA_PERMISSION" -> stringResource(R.string.error_media_permission)
        "BAD_FOLDER" -> stringResource(R.string.error_bad_folder)
        "LOCAL_FILE" -> stringResource(R.string.error_open_file)
        else -> if (code.startsWith("HTTP_") && http.toIntOrNull() != null) {
            stringResource(R.string.error_http, http.toInt())
        } else {
            code
        }
    }
    return stringResource(R.string.sync_last_error, text)
}

private fun tryView(context: android.content.Context, uri: Uri, mime: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
    true
} catch (_: android.content.ActivityNotFoundException) {
    false
}

@Composable
private fun details(entry: RemoteEntry): String {
    val size = entry.size?.let { formatSize(it) }.orEmpty()
    val date = entry.modifiedEpochMs?.let { formatDate(it) }.orEmpty()
    return listOf(size, date).filter { it.isNotEmpty() }.joinToString(" · ")
}

@Composable
private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return stringResource(R.string.size_bytes, bytes)
    val units = listOf(stringResource(R.string.unit_kb), stringResource(R.string.unit_mb), stringResource(R.string.unit_gb))
    var value = bytes.toDouble()
    var unit = units.first()
    for (candidate in units) {
        value /= 1024.0
        unit = candidate
        if (value < 1024) break
    }
    return stringResource(R.string.size_unit, value, unit)
}
