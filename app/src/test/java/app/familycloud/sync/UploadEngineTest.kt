package app.familycloud.sync

import app.familycloud.data.db.UploadedEntity
import app.familycloud.data.webdav.UrlBuilder
import app.familycloud.data.webdav.WebDavError
import app.familycloud.data.webdav.WebDavResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class UploadEngineTest {
    private val base = "https://example.keenetic.link/webdav/"
    private val folder = "Photos/"

    @Test
    fun failedPutRemovesTheRowWhenThePartIsDeleted() = runBlocking {
        val store = MemoryStore()
        val remote = FakeRemote(put = WebDavResult.Err(WebDavError.Network))
        val engine = engine(store, remote)
        val file = file(1, "a.jpg")
        val failure = engine.uploadOne(base, 1, folder, file, "a.jpg")
        assertEquals("NETWORK", failure?.code)
        assertTrue(store.rows.isEmpty())
        assertEquals(3, remote.puts.size)
        assertEquals(1, remote.deletes.size)
        assertEquals(listOf(file), engine.pending(listOf(file), store.rows))
    }

    @Test
    fun cleanupAfterAKilledUploadMakesTheFilePendingAgain() = runBlocking {
        val store = MemoryStore()
        val remote = FakeRemote()
        val engine = engine(store, remote)
        val file = file(7, "a.jpg")
        store.upsert(UploadedEntity(1, 7, "Photos/a.jpg", 10, 20, "Photos/a.part.jpg"))
        engine.cleanupParts(base)
        assertTrue(store.rows.isEmpty())
        assertEquals(listOf(file), engine.pending(listOf(file), store.rows))
    }

    @Test
    fun partThatCannotBeDeletedKeepsTheIncompleteRow() = runBlocking {
        val store = MemoryStore()
        val remote = FakeRemote(delete = WebDavResult.Err(WebDavError.Network))
        val engine = engine(store, remote)
        val file = file(7, "a.jpg")
        store.upsert(UploadedEntity(1, 7, "Photos/a.jpg", 10, 20, "Photos/a.part.jpg"))
        engine.cleanupParts(base)
        assertEquals("Photos/a.part.jpg", store.rows.single().partPath)
        assertEquals(listOf(file), engine.pending(listOf(file), store.rows))
    }

    @Test
    fun successfulMoveClearsThePartAndDropsTheFileFromPending() = runBlocking {
        val store = MemoryStore()
        val remote = FakeRemote(put = WebDavResult.Ok(Unit), move = WebDavResult.Ok(Unit))
        val engine = engine(store, remote)
        val file = file(3, "a.jpg")
        assertNull(engine.uploadOne(base, 1, folder, file, "a.jpg"))
        assertNull(store.rows.single().partPath)
        assertTrue(engine.pending(listOf(file), store.rows).isEmpty())
    }

    @Test
    fun matchingRemoteSizeIsRecordedWithoutPut() = runBlocking {
        val store = MemoryStore()
        val remote = FakeRemote()
        val engine = engine(store, remote)
        val file = file(4, "Clip.mp4", size = 100)
        val failure = engine.uploadBatch(
            base,
            pairId = 1,
            folder = folder,
            files = listOf(file),
            existingSizes = mutableMapOf("clip.mp4" to 100L),
        )
        assertNull(failure)
        assertTrue(remote.puts.isEmpty())
        assertNull(store.rows.single().partPath)
        assertTrue(engine.pending(listOf(file), store.rows).isEmpty())
    }

    @Test
    fun threeNetworkFailuresStopTheRestOfTheBatch() = runBlocking {
        val store = MemoryStore()
        val remote = FakeRemote(put = WebDavResult.Err(WebDavError.Network))
        val engine = engine(store, remote)
        val files = listOf(file(1, "a.jpg"), file(2, "b.jpg"), file(3, "c.jpg"), file(4, "d.jpg"))
        val failure = engine.uploadBatch(base, 1, folder, files, mutableMapOf())
        assertEquals(true, failure?.retryable)
        assertEquals(9, remote.puts.size)
        assertTrue(remote.puts.none { it.contains("d.part") })
    }

    private fun engine(store: MemoryStore, remote: FakeRemote): UploadEngine {
        return UploadEngine(
            store = store,
            remote = remote,
            urls = UrlBuilder(),
            openStream = { _, _ -> ByteArrayInputStream(byteArrayOf(1)) },
            pause = {},
        )
    }

    private fun file(id: Long, name: String, size: Long = 10) = PendingFile(id, name, size, 20, "src-$id")
}

private class MemoryStore : UploadStore {
    val rows = mutableListOf<UploadedEntity>()

    override suspend fun forPair(pairId: Long) = rows.filter { it.pairId == pairId }
    override suspend fun withParts() = rows.filter { it.partPath != null }
    override suspend fun upsert(entity: UploadedEntity) {
        rows.removeAll { it.pairId == entity.pairId && it.mediaStoreId == entity.mediaStoreId }
        rows += entity
    }

    override suspend fun deleteOne(pairId: Long, mediaStoreId: Long) {
        rows.removeAll { it.pairId == pairId && it.mediaStoreId == mediaStoreId }
    }
}

private class FakeRemote(
    private val put: WebDavResult<Unit> = WebDavResult.Ok(Unit),
    private val move: WebDavResult<Unit> = WebDavResult.Ok(Unit),
    private val delete: WebDavResult<Unit> = WebDavResult.Ok(Unit),
) : RemoteFiles {
    val puts = mutableListOf<String>()
    val deletes = mutableListOf<String>()

    override suspend fun put(
        url: String,
        source: InputStream,
        length: Long,
        onBytes: (sent: Long) -> Unit,
    ): WebDavResult<Unit> {
        puts += url
        if (put is WebDavResult.Ok) onBytes(length)
        return put
    }

    override suspend fun move(from: String, to: String) = move
    override suspend fun delete(url: String): WebDavResult<Unit> {
        deletes += url
        return delete
    }
}
