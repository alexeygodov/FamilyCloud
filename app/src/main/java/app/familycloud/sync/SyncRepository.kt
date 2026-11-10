package app.familycloud.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import app.familycloud.R
import app.familycloud.data.db.AppDatabase
import app.familycloud.data.db.PendingUploadEntity
import app.familycloud.data.db.SyncPairEntity
import app.familycloud.data.db.UploadedEntity
import app.familycloud.data.settings.SettingsRepository
import app.familycloud.data.webdav.UrlBuilder
import app.familycloud.data.webdav.WebDavClient
import app.familycloud.data.webdav.WebDavError
import app.familycloud.data.webdav.WebDavResult
import kotlinx.coroutines.delay
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
    private val lock = Mutex()

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
        val known = database.uploadedDao().forPair(pair.id).associateBy { it.mediaStoreId }
        return files.count { file ->
            needsUpload(
                known[file.id]?.let { UploadedSnapshot(it.size, it.dateModified, it.partPath != null) },
                file.size,
                file.dateModifiedMillis,
            )
        }
    }

    suspend fun enqueueUploads(items: List<PendingUploadEntity>) {
        if (items.isEmpty()) return
        database.pendingUploadDao().insert(items)
    }

    suspend fun runSync(
        pairId: Long?,
        bypassConstraints: Boolean,
        token: Int,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): String? = lock.withLock {
        val connection = settings.snapshot() ?: return context.getString(R.string.error_no_connection)
        cleanupParts(connection.baseUrl)
        val selected = database.syncPairDao().all().filter { it.enabled && (pairId == null || it.id == pairId) }
        var firstError: String? = null
        for (pair in selected) {
            if (TransferControl.isCancelled(token)) return null
            if (!bypassConstraints && !constraintsMet(pair)) continue
            val error = syncPair(pair, connection.baseUrl, token, onProgress)
            database.syncPairDao().update(
                pair.copy(
                    lastSyncEpochMs = System.currentTimeMillis(),
                    lastError = error,
                ),
            )
            if (error != null && firstError == null) firstError = error
        }
        firstError
    }

    suspend fun runUploads(token: Int, onProgress: suspend (done: Int, total: Int) -> Unit): String? = lock.withLock {
        val base = settings.snapshot()?.baseUrl ?: return context.getString(R.string.error_no_connection)
        var done = 0
        var firstError: String? = null
        while (true) {
            val item = database.pendingUploadDao().next() ?: break
            if (TransferControl.isCancelled(token)) return null
            val total = done + database.pendingUploadDao().count()
            onProgress(done, total)
            val error = uploadDocument(base, item)
            if (error == null) {
                database.pendingUploadDao().delete(item.id)
                done += 1
            } else if (isTransient(error)) {
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
        token: Int,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): String? {
        val files = try {
            scanner.files(pair.bucketId, pair.includePhotos, pair.includeVideos, pair.onlyNewSinceEpochMs)
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            return context.getString(R.string.error_media_permission)
        }
        val known = database.uploadedDao().forPair(pair.id).associateBy { it.mediaStoreId }
        val pending = files.filter { file ->
            needsUpload(known[file.id]?.let { UploadedSnapshot(it.size, it.dateModified, it.partPath != null) }, file.size, file.dateModifiedMillis)
        }
        val folderUrl = urls.resolve(baseUrl, pair.remoteFolder) ?: return context.getString(R.string.error_bad_folder)
        val listing = when (val result = client.propfind(folderUrl, depth = 1)) {
            is WebDavResult.Err -> return errorText(context, result.error)
            is WebDavResult.Ok -> result.value
        }
        val sizes = listing.filter { !it.isFolder }.associate { it.name to (it.size ?: -1L) }.toMutableMap()
        var fileError: String? = null
        for ((index, file) in pending.withIndex()) {
            if (TransferControl.isCancelled(token)) return null
            onProgress(index, pending.size)
            val decision = decideRemoteName(file.displayName, sizes, file.size)
            if (decision is NameDecision.AlreadyPresent) {
                database.uploadedDao().upsert(
                    UploadedEntity(pair.id, file.id, pair.remoteFolder + file.displayName, file.size, file.dateModifiedMillis, null),
                )
                continue
            }
            val remoteName = (decision as NameDecision.Upload).fileName
            val error = uploadMedia(baseUrl, pair, file, remoteName)
            if (error != null) {
                if (isTransient(error)) return error
                fileError = error
                continue
            }
            sizes[remoteName] = file.size
            onProgress(index + 1, pending.size)
        }
        return fileError
    }

    private suspend fun uploadMedia(
        baseUrl: String,
        pair: SyncPairEntity,
        file: LocalMedia,
        remoteName: String,
    ): String? {
        val finalRelative = pair.remoteFolder + remoteName
        val partRelative = pair.remoteFolder + partName(remoteName)
        val partUrl = urls.resolve(baseUrl, partRelative) ?: return context.getString(R.string.error_bad_path)
        val finalUrl = urls.resolve(baseUrl, finalRelative) ?: return context.getString(R.string.error_bad_path)
        database.uploadedDao().upsert(
            UploadedEntity(pair.id, file.id, finalRelative, file.size, file.dateModifiedMillis, partRelative),
        )
        var uploaded = false
        var lastError: String? = null
        for (attempt in 0 until 3) {
            val stream = context.contentResolver.openInputStream(file.uri) ?: return context.getString(R.string.error_open_file)
            val put = client.put(partUrl, stream, file.size) {}
            stream.close()
            when (put) {
                is WebDavResult.Ok -> {
                    uploaded = true
                    break
                }
                is WebDavResult.Err -> {
                    lastError = errorText(context, put.error)
                    delay(1000L * (1 shl attempt))
                }
            }
        }
        if (!uploaded) return lastError
        return when (val moved = client.move(partUrl, finalUrl)) {
            is WebDavResult.Err -> errorText(context, moved.error)
            is WebDavResult.Ok -> {
                database.uploadedDao().upsert(
                    UploadedEntity(pair.id, file.id, finalRelative, file.size, file.dateModifiedMillis, null),
                )
                null
            }
        }
    }

    private suspend fun uploadDocument(baseUrl: String, item: PendingUploadEntity): String? {
        val folder = if (item.destinationFolder.endsWith("/")) item.destinationFolder else "${item.destinationFolder}/"
        val folderUrl = urls.resolve(baseUrl, folder) ?: return context.getString(R.string.error_bad_folder)
        val listing = when (val result = client.propfind(folderUrl, 1)) {
            is WebDavResult.Err -> return errorText(context, result.error)
            is WebDavResult.Ok -> result.value
        }
        val sizes = listing.filter { !it.isFolder }.associate { it.name to (it.size ?: -1L) }
        val decision = decideRemoteName(item.displayName, sizes, item.size)
        if (decision is NameDecision.AlreadyPresent) return null
        val remoteName = (decision as NameDecision.Upload).fileName
        val partUrl = urls.resolve(baseUrl, folder + partName(remoteName)) ?: return context.getString(R.string.error_bad_path)
        val finalUrl = urls.resolve(baseUrl, folder + remoteName) ?: return context.getString(R.string.error_bad_path)
        val uri = android.net.Uri.parse(item.sourceUri)
        var uploaded = false
        var last: String? = null
        for (attempt in 0 until 3) {
            val stream: InputStream = context.contentResolver.openInputStream(uri) ?: return context.getString(R.string.error_open_file)
            when (val put = client.put(partUrl, stream, item.size) {}) {
                is WebDavResult.Ok -> {
                    stream.close()
                    uploaded = true
                    break
                }
                is WebDavResult.Err -> {
                    stream.close()
                    last = errorText(context, put.error)
                    delay(1000L * (1 shl attempt))
                }
            }
        }
        if (!uploaded) {
            client.delete(partUrl)
            return last
        }
        return when (val moved = client.move(partUrl, finalUrl)) {
            is WebDavResult.Err -> errorText(context, moved.error)
            is WebDavResult.Ok -> null
        }
    }

    private suspend fun cleanupParts(baseUrl: String) {
        database.uploadedDao().withParts().forEach { row ->
            val part = row.partPath ?: return@forEach
            val url = urls.resolve(baseUrl, part) ?: return@forEach
            when (client.delete(url)) {
                is WebDavResult.Ok -> database.uploadedDao().clearPart(row.pairId, row.mediaStoreId)
                is WebDavResult.Err -> Unit
            }
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

    private fun isTransient(message: String): Boolean = isTransientFailure(context, message)
}

fun isTransientFailure(context: Context, message: String?): Boolean {
    if (message == null) return false
    return message == context.getString(R.string.error_network) ||
        message == context.getString(R.string.error_timeout) ||
        message == context.getString(R.string.error_no_connection)
}

fun errorText(context: Context, error: WebDavError): String = when (error) {
    WebDavError.Unauthorized -> context.getString(R.string.error_unauthorized)
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
    is WebDavError.Http -> context.getString(R.string.error_http, error.code)
}
