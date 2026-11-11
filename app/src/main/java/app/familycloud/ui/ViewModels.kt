package app.familycloud.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.familycloud.AppContainer
import app.familycloud.FamilyCloudApp
import app.familycloud.R
import app.familycloud.data.db.PendingUploadEntity
import app.familycloud.data.db.SyncPairEntity
import app.familycloud.data.settings.SettingsRepository
import app.familycloud.data.webdav.FileKind
import app.familycloud.data.webdav.RemoteEntry
import app.familycloud.data.webdav.WebDavRepository
import app.familycloud.data.webdav.WebDavResult
import app.familycloud.data.webdav.kindOf
import app.familycloud.sync.LocalAlbum
import app.familycloud.sync.SyncScheduler
import app.familycloud.sync.errorText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

fun encodeNav(path: String): String {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))
}

fun decodeNav(value: String): String {
    if (value.isEmpty()) return ""
    return String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}

class AppViewModelFactory(
    private val app: FamilyCloudApp,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val container = app.container
        val model = when (modelClass) {
            ConnectionViewModel::class.java -> ConnectionViewModel(app, container.repository, container.settings)
            BrowserViewModel::class.java -> BrowserViewModel(app, container, extras.createSavedStateHandle())
            SyncViewModel::class.java -> SyncViewModel(app, container)
            ImageViewModel::class.java -> ImageViewModel(container, extras.createSavedStateHandle())
            VideoViewModel::class.java -> VideoViewModel(app, container, extras.createSavedStateHandle())
            else -> error("Unknown model ${modelClass.name}")
        }
        @Suppress("UNCHECKED_CAST")
        return model as T
    }
}

class ConnectionViewModel(
    app: Application,
    private val repository: WebDavRepository,
    settings: SettingsRepository,
) : AndroidViewModel(app) {
    val saved = settings.connection
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    val attemptedUrl = MutableStateFlow<String?>(null)

    fun submit(url: String, login: String, password: String, allowHttp: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            val target = repository.normalizedAddress(url)
            attemptedUrl.value = target
            if (url.trim().startsWith("http://", ignoreCase = true) && !allowHttp) {
                error.value = getApplication<Application>().getString(R.string.error_http_off)
                busy.value = false
                return@launch
            }
            when (val result = repository.test(url, login.trim(), password, allowHttp)) {
                is WebDavResult.Ok -> onDone()
                is WebDavResult.Err -> error.value = errorText(getApplication(), result.error)
            }
            busy.value = false
        }
    }
}

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
    var pendingEntry: RemoteEntry? = null
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
                    entries.value = result.value.sortedWith(
                        compareBy<RemoteEntry> { !it.isFolder }.thenBy { it.label.lowercase() },
                    )
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

    fun delete(entry: RemoteEntry) {
        viewModelScope.launch {
            when (val result = container.repository.delete(entry.relativePath)) {
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
        val url = container.repository.fileUrl(entry.relativePath) ?: return
        viewModelScope.launch {
            transferring.value = true
            try {
                when (val result = container.localFiles.saveToDownloads(url, entry.name)) {
                    is WebDavResult.Err -> message.value = errorText(getApplication(), result.error)
                    is WebDavResult.Ok -> message.value = getApplication<Application>().getString(R.string.downloaded)
                }
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
        val entry = pendingEntry ?: return
        pendingEntry = null
        val target = destination(folder, entry.name, entry.isFolder)
        viewModelScope.launch {
            val result = if (pendingCopy) {
                container.repository.copy(entry.relativePath, target)
            } else {
                container.repository.move(entry.relativePath, target)
            }
            when (result) {
                is WebDavResult.Err -> message.value = errorText(getApplication(), result.error)
                is WebDavResult.Ok -> refresh()
            }
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

    private companion object {
        const val PATH = "path"
    }
}

data class PairRow(val pair: SyncPairEntity, val pending: Int)

class SyncViewModel(
    app: Application,
    private val container: AppContainer,
) : AndroidViewModel(app) {
    val rows = MutableStateFlow<List<PairRow>>(emptyList())
    val albums = MutableStateFlow<List<LocalAlbum>>(emptyList())
    var pendingAlbum: LocalAlbum? = null

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val pairs = container.sync.pairs()
            rows.value = pairs.map { pair ->
                val pending = try {
                    container.sync.pendingCount(pair)
                } catch (_: SecurityException) {
                    0
                }
                PairRow(pair, pending)
            }
        }
    }

    fun loadAlbums() {
        viewModelScope.launch {
            albums.value = try {
                container.scanner.albums()
            } catch (_: SecurityException) {
                emptyList()
            }
        }
    }

    fun onFolderPicked(folder: String) {
        val album = pendingAlbum ?: return
        pendingAlbum = null
        viewModelScope.launch {
            container.sync.addPair(album.name, album.bucketId, folder)
            SyncScheduler.refresh(getApplication(), container.sync.pairs())
            reload()
        }
    }

    fun update(pair: SyncPairEntity) {
        viewModelScope.launch {
            container.sync.update(pair)
            SyncScheduler.refresh(getApplication(), container.sync.pairs())
            reload()
        }
    }

    fun setOnlyNew(pair: SyncPairEntity, enabled: Boolean) {
        val since = if (enabled) {
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } else {
            null
        }
        update(pair.copy(onlyNewSinceEpochMs = since))
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            container.sync.deletePair(id)
            androidx.work.WorkManager.getInstance(getApplication()).cancelUniqueWork("sync-$id")
            SyncScheduler.refresh(getApplication(), container.sync.pairs())
            reload()
        }
    }

    fun syncNow() {
        SyncScheduler.syncNow(getApplication())
    }
}

class ImageViewModel(
    container: AppContainer,
    savedState: SavedStateHandle,
) : ViewModel() {
    val path: String = decodeNav(savedState.get<String>("path").orEmpty())
    val name: String = container.urls.fileName(path)
    val url: String? = container.repository.fileUrl(path)
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoViewModel(
    app: Application,
    container: AppContainer,
    savedState: SavedStateHandle,
) : AndroidViewModel(app) {
    val path: String = decodeNav(savedState.get<String>("path").orEmpty())
    val name: String = container.urls.fileName(path)
    val player: ExoPlayer?

    init {
        val url = container.repository.fileUrl(path)
        player = if (url == null) {
            null
        } else {
            ExoPlayer.Builder(app).build().also { exo ->
                val source = ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(container.http))
                    .createMediaSource(MediaItem.fromUri(url))
                exo.setMediaSource(source)
                exo.prepare()
                exo.playWhenReady = true
            }
        }
    }

    override fun onCleared() {
        player?.release()
    }
}
