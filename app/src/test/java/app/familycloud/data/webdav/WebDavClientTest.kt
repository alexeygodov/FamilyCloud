package app.familycloud.data.webdav

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

    private fun response(code: Int, body: String): MockResponse {
        return MockResponse.Builder().code(code).body(body).build()
    }
}
