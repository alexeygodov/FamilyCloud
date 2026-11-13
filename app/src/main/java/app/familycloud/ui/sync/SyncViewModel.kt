package app.familycloud.ui.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.familycloud.AppContainer
import app.familycloud.data.db.SyncPairEntity
import app.familycloud.sync.LocalAlbum
import app.familycloud.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class PairRow(val pair: SyncPairEntity, val pending: Int)

data class SyncDraft(val album: LocalAlbum, val folder: String)

class SyncViewModel(
    app: Application,
    private val container: AppContainer,
) : AndroidViewModel(app) {
    val rows = MutableStateFlow<List<PairRow>>(emptyList())
    val albums = MutableStateFlow<List<LocalAlbum>>(emptyList())
    val draft = MutableStateFlow<SyncDraft?>(null)
    val dismissed = container.settings.dismissedNotices
    var pendingAlbum: LocalAlbum? = null
    private var editing: SyncPairEntity? = null

    fun pickLabel(): String = editing?.albumName ?: pendingAlbum?.name.orEmpty()

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
        val edit = editing
        editing = null
        if (edit != null) {
            update(edit.copy(remoteFolder = folder))
            return
        }
        val album = pendingAlbum ?: return
        draft.value = SyncDraft(album, folder)
    }

    fun beginEdit(pair: SyncPairEntity) {
        pendingAlbum = null
        draft.value = null
        editing = pair
    }

    fun confirmDraft(
        photos: Boolean,
        videos: Boolean,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        onlyNew: Boolean,
    ) {
        val chosen = draft.value ?: return
        draft.value = null
        pendingAlbum = null
        val since = if (onlyNew) {
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } else {
            null
        }
        viewModelScope.launch {
            container.sync.addPair(
                chosen.album.name,
                chosen.album.bucketId,
                chosen.folder,
                includePhotos = photos,
                includeVideos = videos,
                wifiOnly = wifiOnly,
                chargingOnly = chargingOnly,
                onlyNewSinceEpochMs = since,
            )
            SyncScheduler.refresh(getApplication(), container.sync.pairs())
            reload()
        }
    }

    fun cancelDraft() {
        draft.value = null
        pendingAlbum = null
    }

    fun dismissNotice(id: String) {
        viewModelScope.launch { container.settings.dismissNotice(id) }
    }

    fun cancelSync() {
        WorkManager.getInstance(getApplication()).cancelUniqueWork("sync-now")
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
            WorkManager.getInstance(getApplication()).cancelUniqueWork("sync-$id")
            SyncScheduler.refresh(getApplication(), container.sync.pairs())
            reload()
        }
    }

    fun syncNow() {
        SyncScheduler.syncNow(getApplication())
    }
}
