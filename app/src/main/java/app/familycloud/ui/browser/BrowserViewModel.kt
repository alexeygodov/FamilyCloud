package app.familycloud.ui.browser

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.familycloud.AppContainer
import app.familycloud.R
import app.familycloud.data.db.PendingUploadEntity
import app.familycloud.data.webdav.FileKind
import app.familycloud.data.webdav.RemoteEntry
import app.familycloud.data.webdav.SortField
import app.familycloud.data.webdav.SortOrder
import app.familycloud.data.webdav.WebDavResult
import app.familycloud.data.webdav.kindOf
import app.familycloud.data.webdav.sortEntries
import app.familycloud.sync.SyncScheduler
import app.familycloud.sync.errorText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BrowserViewModel(
    app: Application,
    private val container: AppContainer,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(app) {
    val path: StateFlow<String> = savedState.getStateFlow(PATH, "")
    val entries = MutableStateFlow<List<RemoteEntry>>(emptyList())
    val loading = MutableStateFlow(false)
    val transferring = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val failure = MutableStateFlow<String?>(null)
    val sort = MutableStateFlow(savedSort())
    var pendingEntries: List<RemoteEntry> = emptyList()
    var pendingCopy: Boolean = false
    private var listJob: Job? = null

    init {
        viewModelScope.launch {
            path.collect { refresh(clear = true) }
        }
    }

    fun open(relative: String) {
        savedState[PATH] = relative
    }

    fun up() {
        val parent = container.urls.parentRelative(path.value) ?: return
        open(parent)
    }

    fun refresh(clear: Boolean = false) {
        val requested = path.value
        listJob?.cancel()
        if (clear) entries.value = emptyList()
        listJob = viewModelScope.launch {
            loading.value = true
            failure.value = null
            when (val result = container.repository.list(requested)) {
                is WebDavResult.Ok -> if (path.value == requested) {
                    entries.value = sortEntries(result.value, sort.value)
                }
                is WebDavResult.Err -> if (path.value == requested) {
                    val text = errorText(getApplication(), result.error)
                    message.value = text
                    failure.value = text
                }
            }
            if (path.value == requested) loading.value = false
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    fun setSort(order: SortOrder) {
        sort.value = order
        savedState[SORT_FIELD] = order.field.name
        savedState[SORT_ASC] = order.ascending
        entries.value = sortEntries(entries.value, order)
    }

    fun previewUrl(entry: RemoteEntry): String? {
        if (entry.isFolder || kind(entry) != FileKind.Image) return null
        return container.repository.fileUrl(entry.relativePath)
    }

    fun stageTransfer(chosen: List<RemoteEntry>, copy: Boolean) {
        pendingEntries = chosen
        pendingCopy = copy
    }

    fun createFolder(name: String) {
        if (!container.urls.isSafeName(name)) {
            message.value = getApplication<Application>().getString(R.string.error_bad_name)
            return
        }
        viewModelScope.launch {
            when (val result = container.repository.createFolder(path.value, name.trim())) {
                is WebDavResult.Err -> message.value = errorText(getApplication(), result.error)
                is WebDavResult.Ok -> refresh()
            }
        }
    }

    fun rename(entry: RemoteEntry, name: String) {
        if (!container.urls.isSafeName(name)) {
            message.value = getApplication<Application>().getString(R.string.error_bad_name)
            return
        }
        val target = destination(path.value, name.trim(), entry.isFolder)
        viewModelScope.launch {
            when (val result = container.repository.move(entry.relativePath, target)) {
                is WebDavResult.Err -> message.value = errorText(getApplication(), result.error)
                is WebDavResult.Ok -> refresh()
            }
        }
    }

    fun download(entry: RemoteEntry) {
        downloadAll(listOf(entry))
    }

    fun deleteAll(chosen: List<RemoteEntry>) {
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            for (entry in chosen) {
                when (val result = container.repository.delete(entry.relativePath)) {
                    is WebDavResult.Err -> {
                        message.value = errorText(getApplication(), result.error)
                        refresh()
                        return@launch
                    }
                    is WebDavResult.Ok -> Unit
                }
            }
            refresh()
        }
    }

    fun downloadAll(chosen: List<RemoteEntry>) {
        val files = chosen.filter { !it.isFolder }
        if (files.isEmpty()) return
        viewModelScope.launch {
            transferring.value = true
            try {
                for (entry in files) {
                    val url = container.repository.fileUrl(entry.relativePath) ?: continue
                    when (val result = container.localFiles.saveToDownloads(url, entry.name)) {
                        is WebDavResult.Err -> {
                            message.value = errorText(getApplication(), result.error)
                            return@launch
                        }
                        is WebDavResult.Ok -> Unit
                    }
                }
                message.value = getApplication<Application>().getString(R.string.downloaded)
            } finally {
                transferring.value = false
            }
        }
    }

    fun upload(uris: List<Uri>) {
        viewModelScope.launch {
            val folder = path.value
            val items = mutableListOf<PendingUploadEntity>()
            uris.forEach { uri ->
                val name = displayName(uri)
                val size = sizeOf(uri)
                if (name == null || size < 0L) {
                    message.value = getApplication<Application>().getString(R.string.error_unknown_size)
                    return@forEach
                }
                try {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                    Unit
                }
                items += PendingUploadEntity(
                    sourceUri = uri.toString(),
                    displayName = name,
                    size = size,
                    destinationFolder = folder,
                )
            }
            container.sync.enqueueUploads(items)
            if (items.isNotEmpty()) {
                SyncScheduler.enqueueUploads(getApplication())
            }
        }
    }

    fun onFolderPicked(folder: String) {
        val chosen = pendingEntries
        pendingEntries = emptyList()
        if (chosen.isEmpty()) return
        val copy = pendingCopy
        viewModelScope.launch {
            for (entry in chosen) {
                val target = destination(folder, entry.name, entry.isFolder)
                val result = if (copy) {
                    container.repository.copy(entry.relativePath, target)
                } else {
                    container.repository.move(entry.relativePath, target)
                }
                if (result is WebDavResult.Err) {
                    message.value = errorText(getApplication(), result.error)
                    refresh()
                    return@launch
                }
            }
            refresh()
        }
    }

    fun openExternally(entry: RemoteEntry, start: (Uri, String) -> Boolean) {
        openCached(entry) { uri, mime ->
            if (!start(uri, mime)) {
                message.value = getApplication<Application>().getString(R.string.error_open_external)
            }
        }
    }

    fun openCached(entry: RemoteEntry, onReady: (Uri, String) -> Unit) {
        val url = container.repository.fileUrl(entry.relativePath) ?: return
        viewModelScope.launch {
            when (val result = container.localFiles.cacheForView(url, entry.name)) {
                is WebDavResult.Err -> message.value = errorText(getApplication(), result.error)
                is WebDavResult.Ok -> onReady(result.value, container.localFiles.mimeOf(entry.name))
            }
        }
    }

    fun kind(entry: RemoteEntry): FileKind = kindOf(entry.name)

    private fun destination(folder: String, name: String, folderItem: Boolean): String {
        val base = folder.trim('/')
        val relative = if (base.isEmpty()) name else "$base/$name"
        return if (folderItem && !relative.endsWith("/")) "$relative/" else relative
    }

    private fun displayName(uri: Uri): String? {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return null
    }

    private fun sizeOf(uri: Uri): Long {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
                }
            }
        return getApplication<Application>().contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }

    private fun savedSort(): SortOrder {
        val field = SortField.entries.firstOrNull { it.name == savedState.get<String>(SORT_FIELD) } ?: SortField.Name
        return SortOrder(field, savedState.get<Boolean>(SORT_ASC) ?: true)
    }

    private companion object {
        const val PATH = "path"
        const val SORT_FIELD = "sortField"
        const val SORT_ASC = "sortAsc"
    }
}
