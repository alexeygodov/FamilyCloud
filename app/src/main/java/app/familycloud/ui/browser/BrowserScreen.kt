package app.familycloud.ui.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.familycloud.R
import app.familycloud.data.webdav.FileKind
import app.familycloud.data.webdav.RemoteEntry
import app.familycloud.data.webdav.SortField
import app.familycloud.data.webdav.SortOrder
import app.familycloud.sync.TransferKeys
import app.familycloud.ui.components.EmptyState
import app.familycloud.ui.components.ErrorState
import app.familycloud.ui.components.FileGlyph
import app.familycloud.ui.components.LoadingList
import app.familycloud.ui.components.TransferBar
import app.familycloud.ui.components.rememberWorkTransfer
import app.familycloud.ui.encodeNav
import app.familycloud.ui.formatByteSize
import app.familycloud.ui.formatEpoch
import app.familycloud.ui.components.glyphKind
import app.familycloud.ui.components.GlyphKind
import app.familycloud.ui.theme.Dimens
import app.familycloud.ui.theme.FamilyCloudTheme

@Composable
fun BrowserRoute(
    viewModel: BrowserViewModel,
    onConnection: () -> Unit,
    onSync: () -> Unit,
    onChooseDestination: () -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
) {
    val path by viewModel.path.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val canRetry by viewModel.messageCanRetry.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val order by viewModel.sort.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val transfer = rememberWorkTransfer("uploads")
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.upload(uris)
    }
    val manager = remember { WorkManager.getInstance(context) }
    val uploads by manager.getWorkInfosForUniqueWorkFlow("uploads").collectAsStateWithLifecycle(emptyList())
    var watchedUpload by remember { mutableStateOf(false) }
    LaunchedEffect(uploads) {
        val active = uploads.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        val finished = uploads.firstOrNull { it.state == WorkInfo.State.SUCCEEDED }
        if (watchedUpload && !active && finished != null) {
            val problem = finished.outputData.getString(TransferKeys.FAILURE).orEmpty()
            val count = finished.outputData.getInt(TransferKeys.DONE, 0)
            if (problem.isEmpty() && count > 0) {
                viewModel.reportUploaded(count)
                viewModel.refresh()
            }
        }
        watchedUpload = active
    }
    BrowserScreen(
        path = path,
        entries = entries,
        loading = loading,
        message = message,
        messageCanRetry = canRetry,
        failure = failure,
        order = order,
        uploadLabel = transfer?.label,
        uploadFraction = transfer?.fraction,
        onCancelUpload = transfer?.onCancel,
        download = download,
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
        onCancelDownload = viewModel::cancelDownload,
        onUpload = { uploadLauncher.launch(arrayOf("*/*")) },
        onOpenExternal = { entry ->
            viewModel.openExternally(entry) { uri, mime -> tryView(context, uri, mime) }
        },
        kindOfEntry = viewModel::kind,
        isNameValid = viewModel::isNameValid,
        onConnection = onConnection,
        onSync = onSync,
        onChooseDestination = onChooseDestination,
        onOpenImage = onOpenImage,
        onOpenVideo = onOpenVideo,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    path: String,
    entries: List<RemoteEntry>,
    loading: Boolean,
    message: String?,
    messageCanRetry: Boolean,
    failure: String?,
    order: SortOrder,
    uploadLabel: String?,
    uploadFraction: Float?,
    onCancelUpload: (() -> Unit)?,
    download: ActiveDownload?,
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
    onCancelDownload: () -> Unit,
    onUpload: () -> Unit,
    onOpenExternal: (RemoteEntry) -> Unit,
    kindOfEntry: (RemoteEntry) -> FileKind,
    isNameValid: (String) -> Boolean,
    onConnection: () -> Unit,
    onSync: () -> Unit,
    onChooseDestination: () -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val retryLabel = stringResource(R.string.retry)
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var headerMenu by remember { mutableStateOf(false) }
    var sortSheet by rememberSaveable { mutableStateOf(false) }
    var addSheet by rememberSaveable { mutableStateOf(false) }
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var renamePath by rememberSaveable { mutableStateOf<String?>(null) }
    var deletePaths by rememberSaveable(stateSaver = pathListSaver) { mutableStateOf(emptyList()) }
    var selected by rememberSaveable(stateSaver = pathListSaver) { mutableStateOf(emptyList()) }
    val choosing = selected.isNotEmpty()
    BackHandler(enabled = choosing || path.isNotEmpty()) {
        if (choosing) selected = emptyList() else onUp()
    }
    LaunchedEffect(message, messageCanRetry) {
        val text = message ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = text,
            actionLabel = if (messageCanRetry) retryLabel else null,
        )
        onConsumeMessage()
        if (result == SnackbarResult.ActionPerformed) onRefresh()
    }
    LaunchedEffect(entries) {
        val live = entries.map { it.relativePath }.toSet()
        selected = selected.filter { it in live }
    }
    val title = if (path.isEmpty()) {
        stringResource(R.string.files_title)
    } else {
        path.trim('/').substringAfterLast('/')
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
                    onDelete = { deletePaths = selected },
                    onCopy = {
                        onStageTransfer(entries.filter { it.relativePath in selected }, true)
                        selected = emptyList()
                        onChooseDestination()
                    },
                    onRename = if (selected.size == 1) {
                        { renamePath = selected.first() }
                    } else {
                        null
                    },
                    onSelectAll = { selected = entries.map { it.relativePath } },
                )
            } else {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (path.isNotEmpty()) {
                            IconButton(onClick = onUp) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { sortSheet = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort))
                        }
                        IconButton(onClick = onSync) {
                            Icon(Icons.Filled.CloudSync, stringResource(R.string.sync_title))
                        }
                        Box {
                            IconButton(onClick = { headerMenu = true }) {
                                Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions))
                            }
                            DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.refresh)) },
                                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                    onClick = { headerMenu = false; onRefresh() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.connection_title)) },
                                    leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                    onClick = { headerMenu = false; onConnection() },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!choosing) {
                ExtendedFloatingActionButton(
                    expanded = fabExpanded,
                    onClick = { addSheet = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add)) },
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading && entries.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = Dimens.contentMax)
                    .fillMaxWidth()
                    .fillMaxHeight(),
            ) {
                if (path.isNotEmpty()) Breadcrumbs(path, onOpen)
                if (uploadLabel != null) {
                    TransferBar(uploadLabel, uploadFraction, uploading = true, onCancel = onCancelUpload)
                }
                val activeDownload = download
                if (activeDownload != null) {
                    TransferBar(
                        label = stringResource(
                            R.string.download_progress,
                            activeDownload.index,
                            activeDownload.total,
                            activeDownload.name,
                        ),
                        fraction = null,
                        uploading = false,
                        onCancel = onCancelDownload,
                    )
                }
                Text(
                    sortCaption(order),
                    modifier = Modifier.padding(horizontal = Dimens.l, vertical = Dimens.xs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    loading && entries.isEmpty() && failure == null -> LoadingList(Modifier.weight(1f))
                    failure != null && entries.isEmpty() -> ErrorState(failure, onRefresh, Modifier.weight(1f))
                    entries.isEmpty() -> EmptyState(
                        icon = Icons.Filled.FolderOpen,
                        title = stringResource(R.string.empty_folder),
                        text = stringResource(R.string.empty_folder_hint),
                        modifier = Modifier.weight(1f),
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = Dimens.fabClearance),
                    ) {
                        items(entries, key = { it.relativePath }) { entry ->
                            FileRow(
                                entry = entry,
                                marked = entry.relativePath in selected,
                                choosing = choosing,
                                menuOpen = menuFor == entry.relativePath,
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
                                    } else {
                                        openEntry(entry, kindOfEntry, onOpenImage, onOpenVideo, onOpenExternal)
                                    }
                                },
                                onLongClick = { selected = toggle(selected, entry.relativePath) },
                                onMenu = { menuFor = entry.relativePath },
                                onDismissMenu = { menuFor = null },
                                onOpenItem = {
                                    menuFor = null
                                    openEntry(entry, kindOfEntry, onOpenImage, onOpenVideo, onOpenExternal)
                                },
                                onDownload = { menuFor = null; onDownload(entry) },
                                onRename = { menuFor = null; renamePath = entry.relativePath },
                                onMove = {
                                    onStageTransfer(listOf(entry), false)
                                    menuFor = null
                                    onChooseDestination()
                                },
                                onCopy = {
                                    onStageTransfer(listOf(entry), true)
                                    menuFor = null
                                    onChooseDestination()
                                },
                                onDelete = { menuFor = null; deletePaths = listOf(entry.relativePath) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (sortSheet) {
        ModalBottomSheet(onDismissRequest = { sortSheet = false }) {
            SortSheet(order, onSort)
        }
    }
    if (addSheet) {
        ModalBottomSheet(onDismissRequest = { addSheet = false }) {
            Column(Modifier.padding(bottom = Dimens.l)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.upload_files)) },
                    leadingContent = { Icon(Icons.Filled.Upload, null) },
                    modifier = Modifier.clickable { addSheet = false; onUpload() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.new_folder)) },
                    leadingContent = { Icon(Icons.Filled.CreateNewFolder, null) },
                    modifier = Modifier.clickable { addSheet = false; newFolder = true },
                )
            }
        }
    }
    if (newFolder) {
        NameDialog(
            title = stringResource(R.string.new_folder),
            label = stringResource(R.string.folder_name),
            initial = "",
            selectName = false,
            confirmLabel = stringResource(R.string.create),
            isNameValid = isNameValid,
            onDismiss = { newFolder = false },
            onConfirm = { onCreateFolder(it); newFolder = false },
        )
    }
    val renaming = entries.firstOrNull { it.relativePath == renamePath }
    if (renaming != null) {
        NameDialog(
            title = stringResource(R.string.rename),
            label = stringResource(R.string.new_name),
            initial = renaming.name,
            selectName = !renaming.isFolder,
            confirmLabel = stringResource(R.string.save),
            isNameValid = isNameValid,
            onDismiss = { renamePath = null },
            onConfirm = { onRename(renaming, it); renamePath = null },
        )
    }
    val deleting = entries.filter { it.relativePath in deletePaths }
    if (deleting.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deletePaths = emptyList() },
            title = {
                Text(
                    if (deleting.size == 1) {
                        stringResource(R.string.delete_title_one, deleting.first().label)
                    } else {
                        stringResource(R.string.delete_title_many, deleting.size)
                    },
                )
            },
            text = { Text(stringResource(R.string.delete_irreversible)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAll(deleting)
                        selected = emptyList()
                        deletePaths = emptyList()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.delete)) }
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
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onRename: (() -> Unit)?,
    onSelectAll: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.selected_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, stringResource(R.string.clear_selection)) }
        },
        actions = {
            IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, stringResource(R.string.download)) }
            IconButton(onClick = onMove) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.move))
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                        onClick = { menu = false; onCopy() },
                    )
                    val rename = onRename
                    if (rename != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                            onClick = { menu = false; rename() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.select_all)) },
                        leadingIcon = { Icon(Icons.Filled.Check, null) },
                        onClick = { menu = false; onSelectAll() },
                    )
                }
            }
        },
    )
}

@Composable
private fun SortSheet(order: SortOrder, onSort: (SortOrder) -> Unit) {
    Column(Modifier.padding(Dimens.l), verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Text(stringResource(R.string.sort), style = MaterialTheme.typography.titleMedium)
        SortField.entries.forEach { field ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                onSort(order.copy(field = field))
            }) {
                RadioButton(selected = order.field == field, onClick = { onSort(order.copy(field = field)) })
                Text(stringResource(sortLabel(field)))
            }
        }
        val (asc, desc) = directionLabels(order.field)
        val newestFirst = order.field != SortField.Name
        val leftAscending = !newestFirst
        val left = if (leftAscending) asc else desc
        val right = if (leftAscending) desc else asc
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = Dimens.s)) {
            SegmentedButton(
                selected = order.ascending == leftAscending,
                onClick = { onSort(order.copy(ascending = leftAscending)) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(left), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            SegmentedButton(
                selected = order.ascending != leftAscending,
                onClick = { onSort(order.copy(ascending = !leftAscending)) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(right), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileRow(
    entry: RemoteEntry,
    marked: Boolean,
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
    val spoken = rowDescription(entry)
    ListItem(
        headlineContent = {
            Text(entry.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        },
        supportingContent = {
            val line = details(entry)
            if (line.isNotEmpty()) {
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = if (marked) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = {
            AnimatedContent(
                targetState = choosing,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                label = "selection",
            ) { selecting ->
                if (selecting) {
                    SelectionMark(marked)
                } else {
                    FileGlyph(entry.name, entry.isFolder, contentDescription = null, previewUrl = preview)
                }
            }
        },
        trailingContent = {
            if (!choosing) {
                Box {
                    IconButton(onClick = onMenu) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                        if (!entry.isFolder) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open)) },
                                onClick = onOpenItem,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.download)) },
                                leadingIcon = { Icon(Icons.Filled.Download, null) },
                                onClick = onDownload,
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                            onClick = onRename,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                            onClick = onMove,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy)) },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = onCopy,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = onDelete,
                        )
                    }
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                if (choosing) selected = marked
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun SelectionMark(marked: Boolean) {
    Box(
        Modifier
            .size(Dimens.listIcon)
            .background(
                if (marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (marked) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    selectName: Boolean,
    confirmLabel: String,
    isNameValid: (String) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val dot = initial.lastIndexOf('.')
    val end = if (selectName && dot > 0) dot else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, end))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = isNameValid(value.text)
    val showError = value.text.isNotEmpty() && !valid
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                isError = showError,
                supportingText = if (showError) {
                    { Text(stringResource(R.string.error_bad_name)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun Breadcrumbs(path: String, onOpen: (String) -> Unit) {
    if (path.isEmpty()) return
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier.horizontalScroll(scroll).padding(horizontal = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onOpen("") }) {
            Icon(Icons.Filled.Home, stringResource(R.string.home))
        }
        var walked = ""
        parts.forEachIndexed { index, part ->
            walked = if (walked.isEmpty()) "$part/" else "$walked$part/"
            val target = walked
            val last = index == parts.lastIndex
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (last) {
                Text(part, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            } else {
                TextButton(onClick = { onOpen(target) }) {
                    Text(part, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun sortCaption(order: SortOrder): String {
    val field = stringResource(sortLabel(order.field))
    val (asc, desc) = directionLabels(order.field)
    val direction = stringResource(if (order.ascending) asc else desc)
    return stringResource(R.string.sort_caption, field, direction)
}

private fun sortLabel(field: SortField): Int = when (field) {
    SortField.Name -> R.string.sort_name
    SortField.Date -> R.string.sort_date
    SortField.Size -> R.string.sort_size
}

private fun directionLabels(field: SortField): Pair<Int, Int> = when (field) {
    SortField.Name -> R.string.sort_az to R.string.sort_za
    SortField.Date -> R.string.sort_oldest to R.string.sort_newest
    SortField.Size -> R.string.sort_smallest to R.string.sort_largest
}

private fun openEntry(
    entry: RemoteEntry,
    kindOfEntry: (RemoteEntry) -> FileKind,
    onOpenImage: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenExternal: (RemoteEntry) -> Unit,
) {
    val encoded = encodeNav(entry.relativePath)
    when (kindOfEntry(entry)) {
        FileKind.Image -> onOpenImage(encoded)
        FileKind.Video -> onOpenVideo(encoded)
        FileKind.Other -> onOpenExternal(entry)
    }
}

private fun toggle(selected: List<String>, path: String): List<String> {
    return if (path in selected) selected - path else selected + path
}

@Composable
private fun rowDescription(entry: RemoteEntry): String {
    val kind = when {
        entry.isFolder -> stringResource(R.string.kind_folder)
        else -> when (glyphKind(entry.name, folder = false)) {
            GlyphKind.Image -> stringResource(R.string.kind_image)
            GlyphKind.Video -> stringResource(R.string.kind_video)
            else -> stringResource(R.string.kind_file)
        }
    }
    return listOf(entry.label, kind, details(entry)).filter { it.isNotEmpty() }.joinToString(", ")
}

@Composable
private fun details(entry: RemoteEntry): String {
    val size = if (entry.isFolder) "" else entry.size?.let { formatByteSize(it) }.orEmpty()
    val date = entry.modifiedEpochMs?.let { formatEpoch(it) }.orEmpty()
    return listOf(size, date).filter { it.isNotEmpty() }.joinToString(" · ")
}

private val pathListSaver = listSaver<List<String>, String>(save = { it }, restore = { it })

private fun tryView(context: Context, uri: Uri, mime: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BrowserListPreview() {
    FamilyCloudTheme(dynamicColor = false) {
        BrowserScreen(
            path = "photos/",
            entries = listOf(
                RemoteEntry("photos/Camera/", "Camera", true, null, 1_700_000_000_000L),
                RemoteEntry("photos/IMG_2041.jpg", "IMG_2041.jpg", false, 2_400_000L, 1_700_000_000_000L),
            ),
            loading = false,
            message = null,
            messageCanRetry = false,
            failure = null,
            order = SortOrder(SortField.Date, ascending = false),
            uploadLabel = null,
            uploadFraction = null,
            onCancelUpload = null,
            download = null,
            onOpen = {},
            onUp = {},
            onRefresh = {},
            onConsumeMessage = {},
            onSort = {},
            previewUrl = { null },
            onStageTransfer = { _, _ -> },
            onCreateFolder = {},
            onRename = { _, _ -> },
            onDownload = {},
            onDeleteAll = {},
            onDownloadAll = {},
            onCancelDownload = {},
            onUpload = {},
            onOpenExternal = {},
            kindOfEntry = { FileKind.Other },
            isNameValid = { it.isNotBlank() },
            onConnection = {},
            onSync = {},
            onChooseDestination = {},
            onOpenImage = {},
            onOpenVideo = {},
        )
    }
}
