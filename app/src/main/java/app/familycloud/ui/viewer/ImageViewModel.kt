package app.familycloud.ui.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.familycloud.AppContainer
import app.familycloud.data.webdav.FileKind
import app.familycloud.data.webdav.SortField
import app.familycloud.data.webdav.SortOrder
import app.familycloud.data.webdav.WebDavResult
import app.familycloud.data.webdav.kindOf
import app.familycloud.data.webdav.sortEntries
import app.familycloud.ui.decodeNav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ImageViewModel(
    private val container: AppContainer,
    savedState: SavedStateHandle,
) : ViewModel() {
    val path: String = decodeNav(savedState.get<String>("path").orEmpty())
    val images = MutableStateFlow(listOf(path))
    val ready = MutableStateFlow(false)

    init {
        val field = SortField.entries.firstOrNull { it.name == savedState.get<String>("field") } ?: SortField.Name
        val ascending = savedState.get<String>("asc") != "false"
        viewModelScope.launch {
            val parent = if (path.isEmpty()) "" else container.urls.parentRelative(path) ?: ""
            when (val result = container.repository.list(parent)) {
                is WebDavResult.Ok -> {
                    val photos = sortEntries(result.value, SortOrder(field, ascending))
                        .filter { !it.isFolder && kindOf(it.name) == FileKind.Image }
                        .map { it.relativePath }
                    if (path in photos) images.value = photos
                }
                is WebDavResult.Err -> Unit
            }
            ready.value = true
        }
    }

    fun urlOf(relative: String): String? = container.repository.fileUrl(relative)

    fun fileName(relative: String): String = container.urls.fileName(relative)
}
