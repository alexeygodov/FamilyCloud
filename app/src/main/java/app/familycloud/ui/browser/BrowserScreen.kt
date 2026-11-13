package app.familycloud.ui.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.familycloud.ui.theme.Dimens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familycloud.R
import app.familycloud.data.webdav.FileKind
import app.familycloud.data.webdav.RemoteEntry
import app.familycloud.data.webdav.SortField
import app.familycloud.data.webdav.SortOrder
import app.familycloud.data.webdav.kindOf
import app.familycloud.ui.components.TransferBar
import app.familycloud.ui.components.TransferBarState
import app.familycloud.ui.components.rememberWorkTransfer
import app.familycloud.ui.encodeNav
import app.familycloud.ui.formatByteSize
import app.familycloud.ui.formatEpoch
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest

@Composable
fun BrowserRoute(
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
    val path by viewModel.path.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val transferring by viewModel.transferring.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val order by viewModel.sort.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val transfer = rememberWorkTransfer("uploads")
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.upload(uris)
    }
    BrowserScreen(
        path = path,
        entries = entries,
        loading = loading,
        transferring = transferring,
        message = message,
        failure = failure,
        order = order,
        picking = picking,
        onBack = onBack,
        onConnection = onConnection,
        onSync = onSync,
        onConfirmFolder = onConfirmFolder,
        onChooseDestination = onChooseDestination,
        onOpenImage = onOpenImage,
        onOpenVideo = onOpenVideo,
        onOpen = viewModel::open,
        onUp = viewModel::up,
        onRefresh = { viewModel.refresh() },
        onConsumeMessage = viewModel::consumeMessage,
        onSort = viewModel::setSort,
        previewUrl = viewModel::previewUrl,
        onStageTransfer = viewModel::stageTransfer,
        onCreateFolder = viewModel::createFolder,
        onRename = viewModel::rename,
        onDownload = viewModel::download,
        onDeleteAll = viewModel::deleteAll,
        onDownloadAll = viewModel::downloadAll,
        onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
        onOpenExternal = { entry ->
            viewModel.openExternally(entry) { uri, mime -> tryView(context, uri, mime) }
        },
        kindOfEntry = viewModel::kind,
        transfer = transfer,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    path: String,
    entries: List<RemoteEntry>,
    loading: Boolean,
    transferring: Boolean,
    message: String?,
    failure: String?,
    order: SortOrder,
    picking: Boolean,
    onBack: (() -> Unit)?,
    onConnection: () -> Unit,
    onSync: () -> Unit,
    onConfirmFolder: (String) -> Unit,
    onChooseDestination: () -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpen: (String) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onConsumeMessage: () -> Unit,
    onSort: (SortOrder) -> Unit,
    previewUrl: (RemoteEntry) -> String?,
    onStageTransfer: (List<RemoteEntry>, Boolean) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (RemoteEntry, String) -> Unit,
    onDownload: (RemoteEntry) -> Unit,
    onDeleteAll: (List<RemoteEntry>) -> Unit,
    onDownloadAll: (List<RemoteEntry>) -> Unit,
    onUpload: () -> Unit,
    onOpenExternal: (RemoteEntry) -> Unit,
    kindOfEntry: (RemoteEntry) -> FileKind,
    transfer: TransferBarState?,
) {
    val snackbar = remember { SnackbarHostState() }
    var menuEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var folderName by rememberSaveable { mutableStateOf("") }
    var renamePath by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deletePaths by rememberSaveable(stateSaver = pathListSaver) { mutableStateOf(emptyList()) }
    var selected by rememberSaveable(stateSaver = pathListSaver) { mutableStateOf(emptyList()) }
    var sortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        onConsumeMessage()
    }
    LaunchedEffect(entries) {
        val live = entries.map { it.relativePath }.toSet()
        selected = selected.filter { it in live }
    }
    val choosing = selected.isNotEmpty() && !picking
    BackHandler(enabled = choosing || path.isNotEmpty()) {
        if (choosing) selected = emptyList() else onUp()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (choosing) {
                SelectionBar(
                    count = selected.size,
                    onClear = { selected = emptyList() },
                    onDownload = {
                        onDownloadAll(entries.filter { it.relativePath in selected })
                        selected = emptyList()
                    },
                    onMove = {
                        onStageTransfer(entries.filter { it.relativePath in selected }, false)
                        selected = emptyList()
                        onChooseDestination()
                    },
                    onCopy = {
                        onStageTransfer(entries.filter { it.relativePath in selected }, true)
                        selected = emptyList()
                        onChooseDestination()
                    },
                    onRename = if (selected.size == 1) {
                        {
                            val entry = entries.firstOrNull { it.relativePath == selected.first() }
                            if (entry != null) {
                                renamePath = entry.relativePath
                                renameText = entry.name
                            }
                            selected = emptyList()
                        }
                    } else {
                        null
                    },
                    onDelete = { deletePaths = selected },
                )
            } else {
                BrowserBar(
                    path = path,
                    order = order,
                    picking = picking,
                    sortMenu = sortMenu,
                    onSortMenu = { sortMenu = it },
                    onBack = { if (path.isNotEmpty()) onUp() else onBack?.invoke() },
                    showBack = path.isNotEmpty() || onBack != null,
                    onSort = onSort,
                    onConfirmFolder = { onConfirmFolder(path) },
                    onRefresh = onRefresh,
                    onSync = onSync,
                    onConnection = onConnection,
                )
            }
        },
        floatingActionButton = {
            if (!choosing) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.m), horizontalAlignment = Alignment.End) {
                    if (!picking) {
                        SmallFloatingActionButton(onClick = onUpload) {
                            Icon(Icons.Filled.CloudUpload, stringResource(R.string.upload))
                        }
                    }
                    FloatingActionButton(onClick = { newFolder = true }) {
                        Icon(Icons.Filled.Add, stringResource(R.string.new_folder))
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                Breadcrumbs(path, onOpen)
                if (transfer != null) {
                    TransferBar(transfer.label, transfer.fraction, transfer.uploading, transfer.onCancel)
                }
                if (transferring) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failure != null) {
                    Column(Modifier.padding(Dimens.l)) {
                        Text(failure, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRefresh) { Text(stringResource(R.string.retry)) }
                    }
                }
                if (entries.isEmpty() && !loading && failure == null) {
                    Text(stringResource(R.string.empty_folder), modifier = Modifier.padding(Dimens.xl))
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = Dimens.fabClearance)) {
                    items(entries, key = { it.relativePath }) { entry ->
                        EntryRow(
                            entry = entry,
                            marked = entry.relativePath in selected,
                            picking = picking,
                            choosing = choosing,
                            menuOpen = menuEntry?.relativePath == entry.relativePath,
                            preview = previewUrl(entry),
                            onClick = {
                                if (choosing) {
                                    selected = toggle(selected, entry.relativePath)
                                } else if (entry.isFolder) {
                                    val folder = if (entry.relativePath.endsWith("/")) {
                                        entry.relativePath
                                    } else {
                                        "${entry.relativePath}/"
                                    }
                                    onOpen(folder)
                                } else if (!picking) {
                                    val encoded = encodeNav(entry.relativePath)
                                    when (kindOfEntry(entry)) {
                                        FileKind.Image -> onOpenImage(encoded)
                                        FileKind.Video -> onOpenVideo(encoded)
                                        FileKind.Other -> onOpenExternal(entry)
                                    }
                                }
                            },
                            onLongClick = {
                                if (!picking) selected = toggle(selected, entry.relativePath)
                            },
                            onMenu = { menuEntry = entry },
                            onDismissMenu = { menuEntry = null },
                            onOpenItem = {
                                menuEntry = null
                                val encoded = encodeNav(entry.relativePath)
                                when (kindOfEntry(entry)) {
                                    FileKind.Image -> onOpenImage(encoded)
                                    FileKind.Video -> onOpenVideo(encoded)
                                    FileKind.Other -> onOpenExternal(entry)
                                }
                            },
                            onDownload = {
                                menuEntry = null
                                onDownload(entry)
                            },
                            onRename = {
                                menuEntry = null
                                renamePath = entry.relativePath
                                renameText = entry.name
                            },
                            onMove = {
                                onStageTransfer(listOf(entry), false)
                                menuEntry = null
                                onChooseDestination()
                            },
                            onCopy = {
                                onStageTransfer(listOf(entry), true)
                                menuEntry = null
                                onChooseDestination()
                            },
                            onDelete = {
                                menuEntry = null
                                deletePaths = listOf(entry.relativePath)
                            },
                        )
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
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.folder_name)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (folderName.isNotBlank()) onCreateFolder(folderName)
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
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.new_name)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) onRename(renaming, renameText)
                    renamePath = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renamePath = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    val deleting = entries.filter { it.relativePath in deletePaths }
    if (deleting.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deletePaths = emptyList() },
            title = { Text(stringResource(R.string.delete)) },
            text = {
                Text(
                    if (deleting.size == 1) {
                        stringResource(R.string.delete_confirm, deleting.first().label)
                    } else {
                        stringResource(R.string.delete_many, deleting.size)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAll(deleting)
                    selected = emptyList()
                    deletePaths = emptyList()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletePaths = emptyList() }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onDownload: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.selected_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, stringResource(R.string.clear_selection))
            }
        },
        actions = {
            IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, stringResource(R.string.download)) }
            IconButton(onClick = onMove) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.move)) }
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy)) }
            val rename = onRename
            if (rename != null) {
                IconButton(onClick = rename) {
                    Icon(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.rename))
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserBar(
    path: String,
    order: SortOrder,
    picking: Boolean,
    sortMenu: Boolean,
    onSortMenu: (Boolean) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    onSort: (SortOrder) -> Unit,
    onConfirmFolder: () -> Unit,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onConnection: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(if (path.isEmpty()) stringResource(R.string.files_root) else path.trim('/').substringAfterLast('/'))
        },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            }
        },
        actions = {
            Box {
                IconButton(onClick = { onSortMenu(true) }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort))
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { onSortMenu(false) }) {
                    SortField.entries.forEach { field ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when (field) {
                                            SortField.Name -> R.string.sort_name
                                            SortField.Date -> R.string.sort_date
                                            SortField.Size -> R.string.sort_size
                                        },
                                    ),
                                )
                            },
                            onClick = {
                                onSort(order.copy(field = field))
                                onSortMenu(false)
                            },
                            trailingIcon = if (order.field == field) {
                                { Text("✓") }
                            } else {
                                null
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(if (order.ascending) R.string.sort_desc else R.string.sort_asc)) },
                        onClick = {
                            onSort(order.copy(ascending = !order.ascending))
                            onSortMenu(false)
                        },
                    )
                }
            }
            if (picking) {
                TextButton(onClick = onConfirmFolder) { Text(stringResource(R.string.pick_here)) }
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
                IconButton(onClick = onSync) { Icon(Icons.Filled.Sync, stringResource(R.string.sync_title)) }
                IconButton(onClick = onConnection) {
                    Icon(Icons.Filled.Settings, stringResource(R.string.connection_title))
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryRow(
    entry: RemoteEntry,
    marked: Boolean,
    picking: Boolean,
    choosing: Boolean,
    menuOpen: Boolean,
    preview: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpenItem: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(entry.label) },
        supportingContent = { Text(details(entry)) },
        colors = if (marked) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = { EntryGlyph(entry, preview) },
        trailingContent = {
            if (!picking && !choosing) {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
    if (menuOpen) {
        DropdownMenu(expanded = true, onDismissRequest = onDismissMenu) {
            if (!entry.isFolder) {
                DropdownMenuItem(text = { Text(stringResource(R.string.open)) }, onClick = onOpenItem)
                DropdownMenuItem(text = { Text(stringResource(R.string.download)) }, onClick = onDownload)
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = onRename)
            DropdownMenuItem(text = { Text(stringResource(R.string.move)) }, onClick = onMove)
            DropdownMenuItem(text = { Text(stringResource(R.string.copy)) }, onClick = onCopy)
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = onDelete)
        }
    }
}

@Composable
internal fun Breadcrumbs(path: String, onOpen: (String) -> Unit) {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpen("") }) { Text(stringResource(R.string.files_root)) }
        var walked = ""
        parts.forEach { part ->
            walked = if (walked.isEmpty()) "$part/" else "$walked$part/"
            val target = walked
            Text("/")
            TextButton(onClick = { onOpen(target) }) { Text(part) }
        }
    }
}

@Composable
private fun EntryGlyph(entry: RemoteEntry, preview: String?) {
    val description = stringResource(
        when {
            entry.isFolder -> R.string.kind_folder
            kindOf(entry.name) == FileKind.Image -> R.string.kind_image
            kindOf(entry.name) == FileKind.Video -> R.string.kind_video
            else -> R.string.kind_file
        },
    )
    if (preview != null) {
        val context = LocalContext.current
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context).data(preview).size(256, 256).build(),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(Dimens.listIcon),
            loading = { PlainGlyph(entry, description) },
            error = { PlainGlyph(entry, description) },
        )
    } else {
        PlainGlyph(entry, description)
    }
}

@Composable
private fun PlainGlyph(entry: RemoteEntry, description: String) {
    Icon(
        when {
            entry.isFolder -> Icons.Filled.Folder
            kindOf(entry.name) == FileKind.Image -> Icons.Filled.Image
            kindOf(entry.name) == FileKind.Video -> Icons.Filled.Movie
            else -> Icons.AutoMirrored.Filled.InsertDriveFile
        },
        contentDescription = description,
    )
}

@Composable
private fun details(entry: RemoteEntry): String {
    val size = entry.size?.let { formatByteSize(it) }.orEmpty()
    val date = entry.modifiedEpochMs?.let { formatEpoch(it) }.orEmpty()
    return listOf(size, date).filter { it.isNotEmpty() }.joinToString(" · ")
}

private fun toggle(selected: List<String>, path: String): List<String> {
    return if (path in selected) selected - path else selected + path
}

private val pathListSaver = listSaver<List<String>, String>(
    save = { it },
    restore = { it },
)

private fun tryView(context: Context, uri: Uri, mime: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}
