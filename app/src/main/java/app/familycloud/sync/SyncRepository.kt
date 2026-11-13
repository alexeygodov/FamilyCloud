package app.familycloud.sync

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.MediaStore
import app.familycloud.R
import app.familycloud.data.db.AppDatabase
import app.familycloud.data.db.PendingUploadEntity
import app.familycloud.data.db.SyncPairEntity
import app.familycloud.data.db.UploadedEntity
import app.familycloud.data.settings.SettingsRepository
import app.familycloud.data.webdav.UnauthorizedKind
import app.familycloud.data.webdav.UrlBuilder
import app.familycloud.data.webdav.WebDavClient
import app.familycloud.data.webdav.WebDavError
import app.familycloud.data.webdav.WebDavResult
import app.familycloud.data.webdav.kind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

class SyncRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val scanner: MediaScanner,
    private val client: WebDavClient,
    private val settings: SettingsRepository,
    private val urls: UrlBuilder,
) {
    private val syncLock = Mutex()
    private val uploadLock = Mutex()
    private val engine = UploadEngine(
        store = RoomUploadStore(database.uploadedDao()),
        remote = WebDavRemote(client),
        urls = urls,
        openStream = { key, mediaStore -> openOriginal(Uri.parse(key), mediaStore) },
        lengthOf = { key, reported -> byteLength(Uri.parse(key), reported) },
    )

    suspend fun pairs(): List<SyncPairEntity> = database.syncPairDao().all()

    suspend fun addPair(albumName: String, bucketId: String, remoteFolder: String) {
        val folder = if (remoteFolder.endsWith("/")) remoteFolder else "$remoteFolder/"
        database.syncPairDao().insert(
            SyncPairEntity(albumName = albumName, bucketId = bucketId, remoteFolder = folder),
        )
    }

    suspend fun update(pair: SyncPairEntity) {
        database.syncPairDao().update(pair)
    }

    suspend fun deletePair(id: Long) {
        database.syncPairDao().deleteUploads(id)
        database.syncPairDao().delete(id)
    }

    suspend fun pendingCount(pair: SyncPairEntity): Int {
        val files = scanner.files(pair.bucketId, pair.includePhotos, pair.includeVideos, pair.onlyNewSinceEpochMs)
        val known = database.uploadedDao().forPair(pair.id)
        return engine.pending(files.map { it.toPending() }, known).size
    }

    suspend fun enqueueUploads(items: List<PendingUploadEntity>) {
        if (items.isEmpty()) return
        database.pendingUploadDao().insert(items)
    }

    suspend fun runSync(
        pairId: Long?,
        bypassConstraints: Boolean,
        onProgress: (TransferProgress) -> Unit,
    ): SyncFailure? = syncLock.withLock {
        val connection = settings.snapshot() ?: return SyncFailure.NoConnection
        engine.cleanupParts(connection.baseUrl)
        cleanupOrphans(connection.baseUrl)
        val selected = database.syncPairDao().all().filter { it.enabled && (pairId == null || it.id == pairId) }
        var firstError: SyncFailure? = null
        for (pair in selected) {
            currentCoroutineContext().ensureActive()
            if (!bypassConstraints && !constraintsMet(pair)) continue
            val error = syncPair(pair, connection.baseUrl, onProgress)
            database.syncPairDao().update(
                pair.copy(
                    lastSyncEpochMs = System.currentTimeMillis(),
                    lastError = error?.code,
                ),
            )
            if (error != null && firstError == null) firstError = error
        }
        firstError
    }

    suspend fun runUploads(onProgress: (TransferProgress) -> Unit): SyncFailure? = uploadLock.withLock {
        val base = settings.snapshot()?.baseUrl ?: return SyncFailure.NoConnection
        cleanupOrphans(base)
        var done = 0
        var firstError: SyncFailure? = null
        while (true) {
            val item = database.pendingUploadDao().next() ?: break
            currentCoroutineContext().ensureActive()
            val total = done + database.pendingUploadDao().count()
            onProgress(TransferProgress(done, total))
            val error = uploadDocument(base, item) { sent, size ->
                onProgress(TransferProgress(done, total, sent, size))
            }
            if (error == null) {
                database.pendingUploadDao().delete(item.id)
                done += 1
            } else if (error.retryable) {
                firstError = error
                break
            } else {
                database.pendingUploadDao().delete(item.id)
                firstError = error
            }
        }
        firstError
    }

    private suspend fun syncPair(
        pair: SyncPairEntity,
        baseUrl: String,
        onProgress: (TransferProgress) -> Unit,
    ): SyncFailure? {
        val files = try {
            scanner.files(pair.bucketId, pair.includePhotos, pair.includeVideos, pair.onlyNewSinceEpochMs)
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            return SyncFailure.MediaPermission
        }
        val known = database.uploadedDao().forPair(pair.id)
        val pending = engine.pending(files.map { it.toPending() }, known)
        val folderUrl = urls.resolve(baseUrl, pair.remoteFolder) ?: return SyncFailure.BadFolder
        val listing = when (val result = client.propfind(folderUrl, depth = 1, baseUrl)) {
            is WebDavResult.Err -> return SyncFailure.Web(result.error)
            is WebDavResult.Ok -> result.value
        }
        val sizes = listing.filter { !it.isFolder }.associate { it.name to (it.size ?: -1L) }.toMutableMap()
        return engine.uploadBatch(baseUrl, pair.id, pair.remoteFolder, pending, sizes, onProgress)
    }

    private suspend fun uploadDocument(
        baseUrl: String,
        item: PendingUploadEntity,
        onBytes: (sent: Long, size: Long) -> Unit = { _, _ -> },
    ): SyncFailure? {
        val folder = if (item.destinationFolder.endsWith("/")) item.destinationFolder else "${item.destinationFolder}/"
        val folderUrl = urls.resolve(baseUrl, folder) ?: return SyncFailure.BadFolder
        val listing = when (val result = client.propfind(folderUrl, 1, baseUrl)) {
            is WebDavResult.Err -> return SyncFailure.Web(result.error)
            is WebDavResult.Ok -> result.value
        }
        val sizes = listing.filter { !it.isFolder }.associate { it.name to (it.size ?: -1L) }
        val decision = decideRemoteName(item.displayName, sizes, item.size)
        if (decision is NameDecision.AlreadyPresent) return null
        val remoteName = (decision as NameDecision.Upload).fileName
        val outcome = engine.uploadLoose(baseUrl, folder, remoteName, item.size, item.sourceUri, onBytes)
        val orphan = outcome.orphanRelative
        if (orphan != null) database.orphanPartDao().insert(app.familycloud.data.db.OrphanPartEntity(orphan))
        return outcome.failure
    }

    private suspend fun cleanupOrphans(baseUrl: String) {
        database.orphanPartDao().all().forEach { row ->
            val url = urls.resolve(baseUrl, row.remotePath) ?: return@forEach
            if (client.delete(url) is WebDavResult.Ok) database.orphanPartDao().delete(row.remotePath)
        }
    }

    private fun constraintsMet(pair: SyncPairEntity): Boolean {
        if (pair.wifiOnly && !unmetered()) return false
        if (pair.chargingOnly && !charging()) return false
        return true
    }

    private fun unmetered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun charging(): Boolean {
        val battery = context.getSystemService(BatteryManager::class.java) ?: return false
        return battery.isCharging
    }

    private fun openOriginal(uri: Uri, isMediaStore: Boolean): InputStream? {
        val canRequestOriginal = isMediaStore &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (canRequestOriginal) {
            try {
                return context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))
            } catch (_: Exception) {
                Unit
            }
        }
        return context.contentResolver.openInputStream(uri)
    }

    private fun byteLength(uri: Uri, reported: Long): Long {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length >= 0) descriptor.length else reported
            } ?: reported
        } catch (_: Exception) {
            reported
        }
    }

    private fun LocalMedia.toPending() = PendingFile(
        id = id,
        displayName = displayName,
        size = size,
        dateModified = dateModifiedMillis,
        sourceKey = uri.toString(),
        mediaStore = true,
    )
}

private class RoomUploadStore(
    private val dao: app.familycloud.data.db.UploadedDao,
) : UploadStore {
    override suspend fun forPair(pairId: Long) = dao.forPair(pairId)
    override suspend fun withParts() = dao.withParts()
    override suspend fun upsert(entity: UploadedEntity) = dao.upsert(entity)
    override suspend fun deleteOne(pairId: Long, mediaStoreId: Long) = dao.deleteOne(pairId, mediaStoreId)
}

private class WebDavRemote(
    private val client: WebDavClient,
) : RemoteFiles {
    override suspend fun put(url: String, source: InputStream, length: Long, onBytes: (sent: Long) -> Unit) =
        client.put(url, source, length, onBytes)
    override suspend fun move(from: String, to: String) = client.move(from, to)
    override suspend fun delete(url: String) = client.delete(url)
}

fun errorText(context: Context, error: WebDavError): String = when (error) {
    is WebDavError.Unauthorized -> when (error.kind()) {
        UnauthorizedKind.Basic -> context.getString(R.string.error_unauthorized_basic)
        UnauthorizedKind.Digest -> context.getString(R.string.error_unauthorized_digest)
        UnauthorizedKind.Unsupported -> context.getString(R.string.error_unauthorized_scheme, error.schemes.joinToString())
        UnauthorizedKind.Generic -> context.getString(R.string.error_unauthorized)
    }
    WebDavError.Forbidden -> context.getString(R.string.error_forbidden)
    WebDavError.NotFound -> context.getString(R.string.error_not_found)
    WebDavError.MethodNotAllowed -> context.getString(R.string.error_method)
    WebDavError.Conflict -> context.getString(R.string.error_conflict)
    WebDavError.Locked -> context.getString(R.string.error_locked)
    WebDavError.InsufficientStorage -> context.getString(R.string.error_storage)
    WebDavError.Timeout -> context.getString(R.string.error_timeout)
    WebDavError.Ssl -> context.getString(R.string.error_ssl)
    WebDavError.InvalidUrl -> context.getString(R.string.error_url)
    WebDavError.Network -> context.getString(R.string.error_network)
    WebDavError.HttpDisabled -> context.getString(R.string.error_http_off)
    is WebDavError.Http -> context.getString(R.string.error_http, error.code)
}
