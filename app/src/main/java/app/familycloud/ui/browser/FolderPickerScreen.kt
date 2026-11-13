package app.familycloud.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.familycloud.ui.theme.Dimens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familycloud.R
import app.familycloud.data.webdav.RemoteEntry

@Composable
fun FolderPickerRoute(
    viewModel: BrowserViewModel,
    mode: PickMode,
    albumName: String,
    source: String,
    blocked: List<String>,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val path by viewModel.path.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    FolderPickerScreen(
        path = path,
        folders = entries.filter { it.isFolder },
        loading = loading,
        mode = mode,
        albumName = albumName,
        canConfirm = canPlaceHere(mode, path, source, blocked),
        onCancel = onCancel,
        onOpen = { folder ->
            val target = if (folder.endsWith("/")) folder else "$folder/"
            viewModel.open(target)
        },
        onUp = viewModel::up,
        onCreateFolder = viewModel::createFolder,
        onConfirm = { onConfirm(path) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(
    path: String,
    folders: List<RemoteEntry>,
    loading: Boolean,
    mode: PickMode,
    albumName: String,
    canConfirm: Boolean,
    onCancel: () -> Unit,
    onOpen: (String) -> Unit,
    onUp: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var folderName by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = path.isNotEmpty()) { onUp() }
    val title = when (mode) {
        PickMode.Move -> stringResource(R.string.pick_move_title)
        PickMode.Copy -> stringResource(R.string.pick_copy_title)
        PickMode.Album -> stringResource(R.string.pick_album_title, albumName)
    }
    val action = when (mode) {
        PickMode.Move -> stringResource(R.string.pick_move_here)
        PickMode.Copy -> stringResource(R.string.pick_copy_here)
        PickMode.Album -> stringResource(R.string.pick_album_here)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1)
                        Text(
                            if (path.isEmpty()) stringResource(R.string.files_root) else path.trim('/'),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, stringResource(R.string.cancel))
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(Dimens.l),
                horizontalArrangement = Arrangement.spacedBy(Dimens.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { newFolder = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.new_folder), maxLines = 1)
                }
                Button(onClick = onConfirm, enabled = canConfirm, modifier = Modifier.weight(1f)) {
                    Text(action, maxLines = 1)
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Breadcrumbs(path, onOpen)
            if (loading && folders.isEmpty()) {
                Text(stringResource(R.string.loading), modifier = Modifier.padding(Dimens.xl))
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = Dimens.s)) {
                items(folders, key = { it.relativePath }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.label, maxLines = 1) },
                        leadingContent = {
                            Icon(Icons.Filled.Folder, stringResource(R.string.kind_folder))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpen(entry.relativePath)
                            },
                    )
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
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (folderName.isNotBlank()) onCreateFolder(folderName)
                    folderName = ""
                    newFolder = false
                }) { Text(stringResource(R.string.create)) }
            },
            dismissButton = {
                TextButton(onClick = { newFolder = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
