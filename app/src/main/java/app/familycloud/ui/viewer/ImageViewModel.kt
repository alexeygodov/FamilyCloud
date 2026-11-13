package app.familycloud.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.familycloud.AppContainer
import app.familycloud.R
import app.familycloud.sync.errorText
import app.familycloud.data.webdav.FileKind
import app.familycloud.data.webdav.SortField
import app.familycloud.data.webdav.SortOrder
import app.familycloud.data.webdav.WebDavResult
import app.familycloud.data.webdav.kindOf
import app.familycloud.data.webdav.sortEntries
import app.familycloud.ui.decodeNav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class ViewerPhoto(val path: String, val size: Long, val modified: Long?)

class ImageViewModel(
    app: Application,
    private val container: AppContainer,
    savedState: SavedStateHandle,
) : AndroidViewModel(app) {
    val path: String = decodeNav(savedState.get<String>("path").orEmpty())
    val images = MutableStateFlow(listOf(ViewerPhoto(path, 0L, null)))
    val ready = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    init {
        val field = SortField.entries.firstOrNull { it.name == savedState.get<String>("field") } ?: SortField.Name
        val ascending = savedState.get<String>("asc") != "false"
        viewModelScope.launch {
            val parent = if (path.isEmpty()) "" else container.urls.parentRelative(path) ?: ""
            when (val result = container.repository.list(parent)) {
                is WebDavResult.Ok -> {
                    val photos = sortEntries(result.value, SortOrder(field, ascending))
                        .filter { !it.isFolder && kindOf(it.name) == FileKind.Image }
                        .map { ViewerPhoto(it.relativePath, it.size ?: 0L, it.modifiedEpochMs) }
                    if (photos.any { it.path == path }) images.value = photos
                }
                is WebDavResult.Err -> Unit
            }
            ready.value = true
        }
    }

    fun urlOf(relative: String): String? = container.repository.fileUrl(relative)

    fun fileName(relative: String): String = container.urls.fileName(relative)

    fun consumeMessage() {
        message.value = null
    }

    fun download(relative: String) {
        viewModelScope.launch {
            val url = container.repository.fileUrl(relative) ?: return@launch
            val name = container.urls.fileName(relative)
            message.value = when (val result = container.localFiles.saveToDownloads(url, name)) {
                is WebDavResult.Ok -> getApplication<Application>().getString(R.string.downloaded)
                is WebDavResult.Err -> errorText(getApplication(), result.error)
            }
        }
    }

    fun delete(relative: String, onExit: () -> Unit) {
        viewModelScope.launch {
            when (val result = container.repository.delete(relative)) {
                is WebDavResult.Err -> message.value = errorText(getApplication(), result.error)
                is WebDavResult.Ok -> {
                    val next = images.value.filter { it.path != relative }
                    if (next.isEmpty()) onExit() else images.value = next
                }
            }
        }
    }
}
