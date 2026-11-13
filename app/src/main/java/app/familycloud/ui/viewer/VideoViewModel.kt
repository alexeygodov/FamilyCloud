package app.familycloud.ui.viewer

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
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
