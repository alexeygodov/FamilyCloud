package app.familycloud.ui.sync

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familycloud.R
import app.familycloud.data.db.SyncPairEntity
import app.familycloud.sync.LocalAlbum
import app.familycloud.ui.components.EmptyState
import app.familycloud.ui.components.Notice
import app.familycloud.ui.components.NoticeTone
import app.familycloud.ui.components.TransferBarState
import app.familycloud.ui.components.rememberWorkTransfer
import app.familycloud.ui.formatEpoch
import app.familycloud.ui.theme.Dimens
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

enum class SyncBanner { Done, Running, WaitWifi, WaitCharge, Failed }

@Composable
fun SyncRoute(
    viewModel: SyncViewModel,
    onBack: () -> Unit,
    onPickFolder: () -> Unit,
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val dismissed by viewModel.dismissed.collectAsStateWithLifecycle(emptySet())
    val transfer = rememberWorkTransfer("sync-now")
    val context = LocalContext.current
    var showAlbums by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
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
    val banner = rememberBanner(rows, transfer != null)

    SyncScreen(
        rows = rows,
        albums = albums,
        draft = draft,
        dismissed = dismissed,
        showAlbums = showAlbums,
        explainPermission = explain,
        partial = partial,
        locationMissing = locationMissing,
        imagesGranted = imagesGranted,
        batteryOptimized = batteryOptimized,
        banner = banner,
        transfer = transfer,
        onBack = onBack,
        onSyncNow = viewModel::syncNow,
        onCancelSync = viewModel::cancelSync,
        onAddAlbum = {
            if (imagesGranted) {
                viewModel.loadAlbums()
                showAlbums = true
            } else {
                explain = true
            }
        },
        onExplainContinue = {
            explain = false
            launcher.launch(permissions.toTypedArray())
        },
        onDismissExplain = { explain = false },
        onDismissAlbums = { showAlbums = false },
        onPickAlbum = { album ->
            viewModel.pendingAlbum = album
            showAlbums = false
            onPickFolder()
        },
        onUpdate = viewModel::update,
        onOnlyNew = viewModel::setOnlyNew,
        onDelete = viewModel::delete,
        onChangeFolder = { pair ->
            viewModel.beginEdit(pair)
            onPickFolder()
        },
        onConfirmDraft = viewModel::confirmDraft,
        onCancelDraft = viewModel::cancelDraft,
        onDismissNotice = viewModel::dismissNotice,
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        },
        onBatterySettings = {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        },
        onRetry = viewModel::syncNow,
    )
}

@Composable
private fun rememberBanner(rows: List<PairRow>, running: Boolean): SyncBanner {
    val context = LocalContext.current
    if (running) return SyncBanner.Running
    if (rows.any { it.pair.lastError != null }) return SyncBanner.Failed
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
    val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true
    val enabled = rows.filter { it.pair.enabled }
    if (enabled.any { it.pair.wifiOnly } && !unmetered) return SyncBanner.WaitWifi
    if (enabled.any { it.pair.chargingOnly } && !charging) return SyncBanner.WaitCharge
    return SyncBanner.Done
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SyncScreen(
    rows: List<PairRow>,
    albums: List<LocalAlbum>,
    draft: SyncDraft?,
    dismissed: Set<String>,
    showAlbums: Boolean,
    explainPermission: Boolean,
    partial: Boolean,
    locationMissing: Boolean,
    imagesGranted: Boolean,
    batteryOptimized: Boolean,
    banner: SyncBanner,
    transfer: TransferBarState?,
    onBack: () -> Unit,
    onSyncNow: () -> Unit,
    onCancelSync: () -> Unit,
    onAddAlbum: () -> Unit,
    onExplainContinue: () -> Unit,
    onDismissExplain: () -> Unit,
    onDismissAlbums: () -> Unit,
    onPickAlbum: (LocalAlbum) -> Unit,
    onUpdate: (SyncPairEntity) -> Unit,
    onOnlyNew: (SyncPairEntity, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onChangeFolder: (SyncPairEntity) -> Unit,
    onConfirmDraft: (Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onCancelDraft: () -> Unit,
    onDismissNotice: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBatterySettings: () -> Unit,
    onRetry: () -> Unit,
) {
    var removing by remember { mutableStateOf<SyncPairEntity?>(null) }
    var moreNotices by rememberSaveable { mutableStateOf(false) }
    val notices = buildNotices(
        imagesGranted, partial, batteryOptimized, locationMissing, dismissed, moreNotices,
        onOpenSettings, onBatterySettings, onDismissNotice,
    )
    val usedBuckets = rows.map { it.pair.bucketId }.toSet()
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
                    if (banner == SyncBanner.Running) {
                        TextButton(onClick = onCancelSync) { Text(stringResource(R.string.cancel)) }
                    } else {
                        TextButton(onClick = onSyncNow) { Text(stringResource(R.string.sync_now)) }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddAlbum,
                icon = { Icon(Icons.Filled.Photo, contentDescription = null) },
                text = { Text(stringResource(R.string.sync_add)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Dimens.l),
            verticalArrangement = Arrangement.spacedBy(Dimens.m),
        ) {
            item { StatusCard(banner, transfer, rows, onRetry, onCancelSync) }
            items(notices.take(if (moreNotices) notices.size else 2), key = { it.id }) { notice ->
                Notice(
                    tone = notice.tone,
                    text = notice.text,
                    actionLabel = notice.action,
                    onAction = notice.onAction,
                    onDismiss = notice.onDismiss,
                )
            }
            if (!moreNotices && notices.size > 2) {
                item {
                    TextButton(onClick = { moreNotices = true }) {
                        Text(stringResource(R.string.sync_more_notices, notices.size - 2))
                    }
                }
            }
            if (rows.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Photo,
                        title = stringResource(R.string.sync_empty),
                        text = stringResource(R.string.sync_empty_hint),
                    )
                }
            }
            items(rows, key = { it.pair.id }) { row ->
                PairCard(row, onUpdate, onOnlyNew, onChangeFolder) { removing = row.pair }
            }
        }
    }
    if (explainPermission) {
        ModalBottomSheet(onDismissRequest = onDismissExplain) {
            Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
                Text(stringResource(R.string.sync_permission_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.sync_permission_explain))
                TextButton(onClick = onExplainContinue) { Text(stringResource(R.string.sync_continue)) }
            }
        }
    }
    if (showAlbums) {
        ModalBottomSheet(onDismissRequest = onDismissAlbums) {
            Text(
                stringResource(R.string.sync_pick_album),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.s),
            )
            if (albums.isEmpty()) {
                Text(stringResource(R.string.sync_no_albums), modifier = Modifier.padding(Dimens.l))
            }
            albums.forEach { album ->
                val added = album.bucketId in usedBuckets
                TextButton(
                    onClick = { onPickAlbum(album) },
                    enabled = !added,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AlbumLine(album, added)
                }
            }
        }
    }
    if (draft != null) {
        OptionsSheet(draft, onConfirmDraft, onCancelDraft)
    }
    val pending = removing
    if (pending != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.sync_remove_title, pending.albumName)) },
            text = { Text(stringResource(R.string.sync_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(pending.id)
                    removing = null
                }) { Text(stringResource(R.string.sync_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private data class SyncNotice(
    val id: String,
    val tone: NoticeTone,
    val text: String,
    val action: String?,
    val onAction: (() -> Unit)?,
    val onDismiss: (() -> Unit)?,
)

@Composable
private fun buildNotices(
    imagesGranted: Boolean,
    partial: Boolean,
    batteryOptimized: Boolean,
    locationMissing: Boolean,
    dismissed: Set<String>,
    expanded: Boolean,
    onOpenSettings: () -> Unit,
    onBatterySettings: () -> Unit,
    onDismissNotice: (String) -> Unit,
): List<SyncNotice> {
    val items = mutableListOf<SyncNotice>()
    if (!imagesGranted) {
        items += SyncNotice(
            "media", NoticeTone.Error, stringResource(R.string.sync_permission),
            stringResource(R.string.open_settings), onOpenSettings, null,
        )
    }
    if (partial) {
        items += SyncNotice("partial", NoticeTone.Warning, stringResource(R.string.sync_partial), null, null, null)
    }
    if (batteryOptimized && ("battery" !in dismissed || expanded)) {
        if ("battery" !in dismissed) {
            items += SyncNotice(
                "battery", NoticeTone.Warning, stringResource(R.string.sync_battery),
                stringResource(R.string.sync_battery_action), onBatterySettings, { onDismissNotice("battery") },
            )
        }
    }
    if (locationMissing && "location" !in dismissed) {
        items += SyncNotice(
            "location", NoticeTone.Warning, stringResource(R.string.sync_location),
            null, null, { onDismissNotice("location") },
        )
    }
    return items
}

@Composable
private fun StatusCard(
    banner: SyncBanner,
    transfer: TransferBarState?,
    rows: List<PairRow>,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val last = rows.mapNotNull { it.pair.lastSyncEpochMs }.maxOrNull()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            when (banner) {
                SyncBanner.Running -> {
                    Text(transfer?.label ?: stringResource(R.string.sync_working), style = MaterialTheme.typography.titleMedium)
                    if (transfer?.fraction != null) {
                        LinearProgressIndicator(progress = { transfer.fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
                SyncBanner.WaitWifi -> {
                    Icon(Icons.Filled.Wifi, contentDescription = null)
                    Text(stringResource(R.string.sync_waiting_wifi), style = MaterialTheme.typography.titleMedium)
                }
                SyncBanner.WaitCharge -> {
                    Icon(Icons.Filled.BatteryChargingFull, contentDescription = null)
                    Text(stringResource(R.string.sync_waiting_charge), style = MaterialTheme.typography.titleMedium)
                }
                SyncBanner.Failed -> {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    val error = rows.firstOrNull { it.pair.lastError != null }?.pair?.lastError
                    Text(if (error != null) failureLabel(error) else stringResource(R.string.sync_failed))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                SyncBanner.Done -> {
                    Icon(Icons.Filled.CloudDone, contentDescription = null)
                    Text(stringResource(R.string.sync_all_done), style = MaterialTheme.typography.titleMedium)
                    if (last != null) {
                        Text(stringResource(R.string.sync_last_check, formatEpoch(last)))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PairCard(
    row: PairRow,
    onUpdate: (SyncPairEntity) -> Unit,
    onOnlyNew: (SyncPairEntity, Boolean) -> Unit,
    onChangeFolder: (SyncPairEntity) -> Unit,
    onRemove: () -> Unit,
) {
    val pair = row.pair
    var menu by remember { mutableStateOf(false) }
    val content = if (pair.enabled) {
        LocalContentColor.current
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalContentColor provides content) {
            Column(Modifier.padding(Dimens.m), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Photo, contentDescription = null, modifier = Modifier.size(Dimens.preview))
                    Column(Modifier.weight(1f).padding(horizontal = Dimens.m)) {
                        Text(pair.albumName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(Dimens.m))
                            Text(
                                pair.remoteFolder,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Switch(
                        checked = pair.enabled,
                        onCheckedChange = { onUpdate(pair.copy(enabled = it)) },
                    )
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sync_change_folder)) },
                            onClick = { menu = false; onChangeFolder(pair) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sync_remove)) },
                            onClick = { menu = false; onRemove() },
                        )
                    }
                }
                val last = pair.lastSyncEpochMs?.let { formatEpoch(it) } ?: stringResource(R.string.sync_never)
                Text(stringResource(R.string.sync_pair_status, row.pending, last))
                if (pair.lastError != null) {
                    Text(failureLabel(pair.lastError), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onUpdate(pair) }) { Text(stringResource(R.string.retry)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                    FilterChip(
                        selected = pair.includePhotos,
                        onClick = { onUpdate(pair.copy(includePhotos = !pair.includePhotos)) },
                        label = { Text(stringResource(R.string.sync_photos)) },
                        enabled = pair.enabled,
                    )
                    FilterChip(
                        selected = pair.includeVideos,
                        onClick = { onUpdate(pair.copy(includeVideos = !pair.includeVideos)) },
                        label = { Text(stringResource(R.string.sync_videos)) },
                        enabled = pair.enabled,
                    )
                    FilterChip(
                        selected = pair.wifiOnly,
                        onClick = { onUpdate(pair.copy(wifiOnly = !pair.wifiOnly)) },
                        label = { Text(stringResource(R.string.sync_wifi)) },
                        enabled = pair.enabled,
                    )
                    FilterChip(
                        selected = pair.chargingOnly,
                        onClick = { onUpdate(pair.copy(chargingOnly = !pair.chargingOnly)) },
                        label = { Text(stringResource(R.string.sync_while_charging)) },
                        enabled = pair.enabled,
                    )
                    FilterChip(
                        selected = pair.onlyNewSinceEpochMs != null,
                        onClick = { onOnlyNew(pair, pair.onlyNewSinceEpochMs == null) },
                        label = { Text(stringResource(R.string.sync_only_new)) },
                        enabled = pair.enabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumLine(album: LocalAlbum, added: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (album.coverUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(album.coverUri).build(),
                contentDescription = null,
                modifier = Modifier.size(Dimens.previewLarge).clip(RoundedCornerShape(Dimens.s)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Filled.Photo, contentDescription = null, modifier = Modifier.size(Dimens.previewLarge))
        }
        Column(Modifier.padding(start = Dimens.m).weight(1f)) {
            Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.sync_album_count, album.count), style = MaterialTheme.typography.bodySmall)
        }
        if (added) Text(stringResource(R.string.sync_added))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OptionsSheet(
    draft: SyncDraft,
    onConfirm: (Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var photos by rememberSaveable { mutableStateOf(true) }
    var videos by rememberSaveable { mutableStateOf(true) }
    var wifi by rememberSaveable { mutableStateOf(true) }
    var charging by rememberSaveable { mutableStateOf(false) }
    var onlyNew by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.m)) {
            Text(stringResource(R.string.sync_what), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.s)) {
                FilterChip(photos, { photos = !photos }, label = { Text(stringResource(R.string.sync_photos)) })
                FilterChip(videos, { videos = !videos }, label = { Text(stringResource(R.string.sync_videos)) })
                FilterChip(wifi, { wifi = !wifi }, label = { Text(stringResource(R.string.sync_wifi)) })
                FilterChip(charging, { charging = !charging }, label = { Text(stringResource(R.string.sync_while_charging)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sync_only_new), modifier = Modifier.weight(1f))
                Switch(checked = onlyNew, onCheckedChange = { onlyNew = it })
            }
            Text(stringResource(R.string.sync_only_new_hint, draft.album.count))
            TextButton(onClick = { onConfirm(photos, videos, wifi, charging, onlyNew) }) {
                Text(stringResource(R.string.sync_done))
            }
        }
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
        "UNAUTHORIZED" -> stringResource(R.string.error_unauthorized_short)
        "UNAUTHORIZED_BASIC" -> stringResource(R.string.error_unauthorized_short)
        "UNAUTHORIZED_DIGEST" -> stringResource(R.string.error_unauthorized_short)
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
    return text
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
