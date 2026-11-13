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
            WorkManager.getInstance(getApplication()).cancelUniqueWork("sync-$id")
            SyncScheduler.refresh(getApplication(), container.sync.pairs())
            reload()
        }
    }

    fun syncNow() {
        SyncScheduler.syncNow(getApplication())
    }
}
