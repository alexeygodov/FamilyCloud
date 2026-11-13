package app.familycloud.ui.viewer

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.familycloud.R
import app.familycloud.data.webdav.WebDavResult
import app.familycloud.sync.errorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.familycloud.AppContainer
import app.familycloud.ui.decodeNav

@OptIn(UnstableApi::class)
class VideoViewModel(
    app: Application,
    private val container: AppContainer,
    savedState: SavedStateHandle,
) : AndroidViewModel(app) {
    val path: String = decodeNav(savedState.get<String>("path").orEmpty())
    val name: String = container.urls.fileName(path)
    val player: ExoPlayer?
    val message = MutableStateFlow<String?>(null)

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

    fun retry() {
        player?.prepare()
        player?.playWhenReady = true
    }

    fun download() {
        viewModelScope.launch {
            val url = container.repository.fileUrl(path) ?: return@launch
            message.value = when (val result = container.localFiles.saveToDownloads(url, name)) {
                is WebDavResult.Ok -> getApplication<Application>().getString(R.string.downloaded)
                is WebDavResult.Err -> errorText(getApplication(), result.error)
            }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    override fun onCleared() {
        player?.release()
    }
}
