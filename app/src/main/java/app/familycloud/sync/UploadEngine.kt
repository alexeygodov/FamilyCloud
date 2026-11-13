package app.familycloud.sync

import app.familycloud.data.db.UploadedEntity
import app.familycloud.data.webdav.UrlBuilder
import app.familycloud.data.webdav.WebDavError
import app.familycloud.data.webdav.WebDavResult
import app.familycloud.data.webdav.retries
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.InputStream

interface UploadStore {
    suspend fun forPair(pairId: Long): List<UploadedEntity>
    suspend fun withParts(): List<UploadedEntity>
    suspend fun upsert(entity: UploadedEntity)
    suspend fun deleteOne(pairId: Long, mediaStoreId: Long)
}

interface RemoteFiles {
    suspend fun put(
        url: String,
        source: InputStream,
        length: Long,
        onBytes: (sent: Long) -> Unit = {},
    ): WebDavResult<Unit>
    suspend fun move(from: String, to: String): WebDavResult<Unit>
    suspend fun delete(url: String): WebDavResult<Unit>
}

data class PendingFile(
    val id: Long,
    val displayName: String,
    val size: Long,
    val dateModified: Long,
    val sourceKey: String,
    val mediaStore: Boolean = true,
)

data class LooseUpload(val failure: SyncFailure?, val orphanRelative: String?)

class UploadEngine(
    private val store: UploadStore,
    private val remote: RemoteFiles,
    private val urls: UrlBuilder,
    private val openStream: suspend (sourceKey: String, mediaStore: Boolean) -> InputStream?,
    private val lengthOf: (sourceKey: String, reported: Long) -> Long = { _, reported -> reported },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    fun pending(files: List<PendingFile>, known: List<UploadedEntity>): List<PendingFile> {
        val byId = known.associateBy { it.mediaStoreId }
        return files.filter { file ->
            val row = byId[file.id]
            needsUpload(
                row?.let { UploadedSnapshot(it.size, it.dateModified, it.partPath != null) },
                file.size,
                file.dateModified,
            )
        }
    }

    suspend fun cleanupParts(baseUrl: String) {
        store.withParts().forEach { row ->
            val part = row.partPath ?: return@forEach
            val url = urls.resolve(baseUrl, part) ?: return@forEach
            if (remote.delete(url) is WebDavResult.Ok) {
                store.deleteOne(row.pairId, row.mediaStoreId)
            }
        }
    }

    suspend fun uploadBatch(
        baseUrl: String,
        pairId: Long,
        folder: String,
        files: List<PendingFile>,
        existingSizes: MutableMap<String, Long>,
        onProgress: (TransferProgress) -> Unit = {},
    ): SyncFailure? {
        var shown: SyncFailure? = null
        var retry: SyncFailure? = null
        var consecutiveNetworkFailures = 0
        for ((index, file) in files.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(TransferProgress(index, files.size, name = file.displayName))
            when (val decision = decideRemoteName(file.displayName, existingSizes, file.size)) {
                NameDecision.AlreadyPresent -> {
                    store.upsert(record(pairId, file, folder + file.displayName, part = null))
                    consecutiveNetworkFailures = 0
                }
                is NameDecision.Upload -> {
                    val problem = uploadOne(baseUrl, pairId, folder, file, decision.fileName) { sent, size ->
                        onProgress(TransferProgress(index, files.size, sent, size, decision.fileName))
                    }
                    if (problem == null) {
                        consecutiveNetworkFailures = 0
                        existingSizes[decision.fileName] = file.size
                        onProgress(TransferProgress(index + 1, files.size, file.size, file.size, decision.fileName))
                        continue
                    }
                    shown = problem
                    if (problem.retryable) {
                        retry = problem
                        if (++consecutiveNetworkFailures >= 3) return problem
                    } else {
                        consecutiveNetworkFailures = 0
                    }
                }
            }
        }
        return retry ?: shown
    }

    suspend fun uploadOne(
        baseUrl: String,
        pairId: Long,
        folder: String,
        file: PendingFile,
        remoteName: String,
        onBytes: (sent: Long, size: Long) -> Unit = { _, _ -> },
    ): SyncFailure? {
        val finalRelative = folder + remoteName
        val partRelative = folder + partName(remoteName)
        val partUrl = urls.resolve(baseUrl, partRelative) ?: return SyncFailure.BadFolder
        val finalUrl = urls.resolve(baseUrl, finalRelative) ?: return SyncFailure.BadFolder
        val bytes = lengthOf(file.sourceKey, file.size)
        if (bytes < 0L) return SyncFailure.LocalFile
        store.upsert(record(pairId, file, finalRelative, partRelative))
        var uploaded = false
        var last: WebDavError? = null
        for (attempt in 0 until 3) {
            val stream = openStream(file.sourceKey, file.mediaStore)
            if (stream == null) {
                dropPart(partUrl, pairId, file.id)
                return SyncFailure.LocalFile
            }
            when (val put = remote.put(partUrl, stream, bytes) { sent -> onBytes(sent, bytes) }) {
                is WebDavResult.Ok -> {
                    stream.close()
                    uploaded = true
                    break
                }
                is WebDavResult.Err -> {
                    stream.close()
                    last = put.error
                    if (!put.error.retries() || attempt == 2) break
                    pause(1000L * (1 shl attempt))
                }
            }
        }
        if (!uploaded) {
            dropPart(partUrl, pairId, file.id)
            return SyncFailure.Web(last ?: WebDavError.Network)
        }
        return when (val moved = remote.move(partUrl, finalUrl)) {
            is WebDavResult.Err -> SyncFailure.Web(moved.error)
            is WebDavResult.Ok -> {
                store.upsert(record(pairId, file, finalRelative, part = null))
                null
            }
        }
    }

    suspend fun uploadLoose(
        baseUrl: String,
        folder: String,
        remoteName: String,
        size: Long,
        sourceKey: String,
        onBytes: (sent: Long, size: Long) -> Unit = { _, _ -> },
    ): LooseUpload {
        val partRelative = folder + partName(remoteName)
        val partUrl = urls.resolve(baseUrl, partRelative) ?: return LooseUpload(SyncFailure.BadFolder, null)
        val finalUrl = urls.resolve(baseUrl, folder + remoteName) ?: return LooseUpload(SyncFailure.BadFolder, null)
        val bytes = lengthOf(sourceKey, size)
        if (bytes < 0L) return LooseUpload(SyncFailure.LocalFile, null)
        var uploaded = false
        var last: WebDavError? = null
        for (attempt in 0 until 3) {
            val stream = openStream(sourceKey, false)
            if (stream == null) {
                return LooseUpload(SyncFailure.LocalFile, orphanIfLeft(partUrl, partRelative))
            }
            when (val put = remote.put(partUrl, stream, bytes) { sent -> onBytes(sent, bytes) }) {
                is WebDavResult.Ok -> {
                    stream.close()
                    uploaded = true
                    break
                }
                is WebDavResult.Err -> {
                    stream.close()
                    last = put.error
                    if (!put.error.retries() || attempt == 2) break
                    pause(1000L * (1 shl attempt))
                }
            }
        }
        if (!uploaded) {
            return LooseUpload(SyncFailure.Web(last ?: WebDavError.Network), orphanIfLeft(partUrl, partRelative))
        }
        return when (val moved = remote.move(partUrl, finalUrl)) {
            is WebDavResult.Err -> LooseUpload(SyncFailure.Web(moved.error), partRelative)
            is WebDavResult.Ok -> LooseUpload(null, null)
        }
    }

    private suspend fun dropPart(partUrl: String, pairId: Long, mediaId: Long) {
        if (remote.delete(partUrl) is WebDavResult.Ok) {
            store.deleteOne(pairId, mediaId)
        }
    }

    private suspend fun orphanIfLeft(partUrl: String, partRelative: String): String? {
        return if (remote.delete(partUrl) is WebDavResult.Ok) null else partRelative
    }

    private fun record(pairId: Long, file: PendingFile, remotePath: String, part: String?): UploadedEntity {
        return UploadedEntity(pairId, file.id, remotePath, file.size, file.dateModified, part)
    }
}
