package app.familycloud.data.webdav

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

class WebDavClientTest {
    private val server = MockWebServer()
    private val client = WebDavClient(OkHttpClient())

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun propfind207ParsesAndSendsDepth() = runBlocking {
        server.enqueue(response(207, "<multistatus></multistatus>"))
        val result = client.propfind(server.url("/webdav/").toString(), depth = 1)
        assertTrue(result is WebDavResult.Ok)
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.headers["Depth"])
    }

    @Test
    fun mapsErrorStatuses() = runBlocking {
        listOf(
            401 to WebDavError.Unauthorized,
            404 to WebDavError.NotFound,
            405 to WebDavError.MethodNotAllowed,
            409 to WebDavError.Conflict,
            507 to WebDavError.InsufficientStorage,
        ).forEach { (code, error) ->
            server.enqueue(response(code, ""))
            val result = client.propfind(server.url("/webdav/").toString(), depth = 0)
            assertEquals(error, (result as WebDavResult.Err).error)
        }
    }

    @Test
    fun putStreamsBodyWithoutBufferingWholeFileInAssertion() = runBlocking {
        server.enqueue(response(201, ""))
        val payload = "hello-webdav".toByteArray()
        val result = client.put(
            server.url("/webdav/a.txt").toString(),
            ByteArrayInputStream(payload),
            payload.size.toLong(),
        )
        assertTrue(result is WebDavResult.Ok)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals(payload.size.toLong(), request.bodySize)
    }

    @Test
    fun moveCopyMkcolAndDeleteSendTheMethod() = runBlocking {
        val from = server.url("/webdav/a.txt").toString()
        val to = server.url("/webdav/b.txt").toString()
        server.enqueue(response(201, ""))
        assertTrue(client.mkcol(server.url("/webdav/dir/").toString()) is WebDavResult.Ok)
        assertEquals("MKCOL", server.takeRequest().method)

        server.enqueue(response(204, ""))
        assertTrue(client.delete(from) is WebDavResult.Ok)
        assertEquals("DELETE", server.takeRequest().method)

        server.enqueue(response(201, ""))
        assertTrue(client.move(from, to) is WebDavResult.Ok)
        val move = server.takeRequest()
        assertEquals("MOVE", move.method)
        assertEquals(to, move.headers["Destination"])
        assertEquals("F", move.headers["Overwrite"])

        server.enqueue(response(201, ""))
        assertTrue(client.copy(from, to, overwrite = true) is WebDavResult.Ok)
        val copy = server.takeRequest()
        assertEquals("COPY", copy.method)
        assertEquals("T", copy.headers["Overwrite"])
    }

    @Test
    fun probeSendsBasicHeaderOnlyWhenHttpIsAllowed() = runBlocking {
        server.enqueue(response(207, "<multistatus></multistatus>"))
        val allowed = client.authenticated("u", "p", allowHttp = true)
            .propfind(server.url("/webdav/").toString(), 0)
        assertTrue(allowed is WebDavResult.Ok)
        assertTrue(server.takeRequest().headers["Authorization"]!!.startsWith("Basic "))

        val blocked = client.authenticated("u", "p", allowHttp = false)
            .propfind(server.url("/webdav/").toString(), 0)
        assertEquals(WebDavError.HttpDisabled, (blocked as WebDavResult.Err).error)
    }

    @Test
    fun cancelStopsPropfindBeforeTheResponse() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(207).body("<a/>").headersDelay(10, TimeUnit.SECONDS).build(),
        )
        val job = launch {
            client.propfind(server.url("/webdav/").toString(), 0)
        }
        delay(200)
        val started = System.nanoTime()
        job.cancel()
        job.join()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000)
    }

    private fun response(code: Int, body: String): MockResponse {
        return MockResponse.Builder().code(code).body(body).build()
    }
}
