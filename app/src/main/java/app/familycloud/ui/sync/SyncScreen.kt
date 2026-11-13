package app.familycloud.ui.sync

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familycloud.R
import app.familycloud.data.db.SyncPairEntity
import app.familycloud.sync.LocalAlbum
import app.familycloud.ui.formatEpoch

@Composable
fun SyncRoute(
    viewModel: SyncViewModel,
    onBack: () -> Unit,
    onPickFolder: () -> Unit,
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAlbums by remember { mutableStateOf(false) }
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
    val imagesGranted = mediaGranted(context)
    val locationMissing = ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.ACCESS_MEDIA_LOCATION,
    ) != android.content.pm.PackageManager.PERMISSION_GRANTED

    SyncScreen(
        rows = rows,
        albums = albums,
        showAlbums = showAlbums,
        partial = partial,
        locationMissing = locationMissing,
        imagesGranted = imagesGranted,
        batteryOptimized = batteryOptimized,
        onBack = onBack,
        onSyncNow = viewModel::syncNow,
        onAddAlbum = {
            if (imagesGranted) {
                viewModel.loadAlbums()
                showAlbums = true
            } else {
                launcher.launch(permissions.toTypedArray())
            }
        },
        onDismissAlbums = { showAlbums = false },
        onPickAlbum = { album ->
            viewModel.pendingAlbum = album
            showAlbums = false
            onPickFolder()
        },
        onUpdate = viewModel::update,
        onOnlyNew = viewModel::setOnlyNew,
        onDelete = viewModel::delete,
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        },
        onBatterySettings = {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    rows: List<PairRow>,
    albums: List<LocalAlbum>,
    showAlbums: Boolean,
    partial: Boolean,
    locationMissing: Boolean,
    imagesGranted: Boolean,
    batteryOptimized: Boolean,
    onBack: () -> Unit,
    onSyncNow: () -> Unit,
    onAddAlbum: () -> Unit,
    onDismissAlbums: () -> Unit,
    onPickAlbum: (LocalAlbum) -> Unit,
    onUpdate: (SyncPairEntity) -> Unit,
    onOnlyNew: (SyncPairEntity, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onBatterySettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onSyncNow) {
                        Icon(Icons.Filled.Sync, stringResource(R.string.sync_now))
                    }
                    IconButton(onClick = onAddAlbum) {
                        Icon(Icons.Filled.Add, stringResource(R.string.sync_add))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (partial) {
                item { Text(stringResource(R.string.sync_partial), color = MaterialTheme.colorScheme.error) }
            }
            if (locationMissing) {
                item { Text(stringResource(R.string.sync_location)) }
            }
            if (!imagesGranted) {
                item {
                    Column {
                        Text(stringResource(R.string.sync_permission))
                        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
                    }
                }
            }
            if (batteryOptimized) {
                item {
                    Column {
                        Text(stringResource(R.string.sync_battery))
                        TextButton(onClick = onBatterySettings) { Text(stringResource(R.string.sync_battery_action)) }
                    }
                }
            }
            if (rows.isEmpty()) {
                item { Text(stringResource(R.string.sync_empty)) }
            }
            items(rows, key = { it.pair.id }) { row ->
                PairBlock(row, onUpdate, onOnlyNew, onDelete)
            }
        }
    }
    if (showAlbums) {
        AlertDialog(
            onDismissRequest = onDismissAlbums,
            title = { Text(stringResource(R.string.sync_pick_album)) },
            text = {
                if (albums.isEmpty()) {
                    Text(stringResource(R.string.sync_no_albums))
                } else {
                    LazyColumn {
                        items(albums, key = { it.bucketId }) { album ->
                            TextButton(onClick = { onPickAlbum(album) }) { Text(album.name) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismissAlbums) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PairBlock(
    row: PairRow,
    onUpdate: (SyncPairEntity) -> Unit,
    onOnlyNew: (SyncPairEntity, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val pair = row.pair
    Column(Modifier.fillMaxWidth()) {
        Text(pair.albumName, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.sync_folder, pair.remoteFolder))
        if (pair.lastSyncEpochMs != null) {
            Text(stringResource(R.string.sync_last, formatEpoch(pair.lastSyncEpochMs)))
        }
        Text(stringResource(R.string.sync_pending, row.pending))
        if (pair.lastError != null) {
            Text(failureLabel(pair.lastError), color = MaterialTheme.colorScheme.error)
        }
        ToggleRow(stringResource(R.string.sync_enabled), pair.enabled) { onUpdate(pair.copy(enabled = it)) }
        ToggleRow(stringResource(R.string.sync_photos), pair.includePhotos) { onUpdate(pair.copy(includePhotos = it)) }
        ToggleRow(stringResource(R.string.sync_videos), pair.includeVideos) { onUpdate(pair.copy(includeVideos = it)) }
        ToggleRow(stringResource(R.string.sync_wifi), pair.wifiOnly) { onUpdate(pair.copy(wifiOnly = it)) }
        ToggleRow(stringResource(R.string.sync_charging), pair.chargingOnly) { onUpdate(pair.copy(chargingOnly = it)) }
        ToggleRow(stringResource(R.string.sync_only_new), pair.onlyNewSinceEpochMs != null) { onOnlyNew(pair, it) }
        TextButton(onClick = { onDelete(pair.id) }) { Text(stringResource(R.string.sync_remove)) }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
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
        "UNAUTHORIZED_BASIC" -> stringResource(R.string.error_unauthorized_basic)
        "UNAUTHORIZED_DIGEST" -> stringResource(R.string.error_unauthorized_digest)
        "UNAUTHORIZED_SCHEME" -> stringResource(R.string.error_unauthorized_unsupported)
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

private fun mediaGranted(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 33) {
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
}
