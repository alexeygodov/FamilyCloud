package app.familycloud.data.webdav

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthSchemeTest {
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
    fun digestMatchesTheRfc2617Example() {
        val challenge = DigestChallenge(
            realm = "testrealm@host.com",
            nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093",
            opaque = "5ccc069c403ebaf9f0171e9517f40e41",
            algorithm = "MD5",
            useQop = true,
            stale = false,
        )
        val response = challenge.response("GET", "/dir/index.html", "Mufasa", "Circle Of Life", "00000001", "0a4f113b")
        assertEquals("6629fae49393a05397450978507c4ef1", response)
    }

    @Test
    fun detectsBasicWhenOnlyBasicIsOffered() = runBlocking {
        server.enqueue(unauthorized("Basic realm=\"Keenetic\""))
        server.enqueue(response(207))

        val result = client.detectScheme(url(), "u", "p", allowHttp = true)

        assertEquals(WebDavResult.Ok(AuthScheme.Basic), result)
        assertNull(server.takeRequest().headers["Authorization"])
        assertTrue(server.takeRequest().headers["Authorization"]!!.startsWith("Basic "))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun detectsDigestAndPrefersItOverBasic() = runBlocking {
        val both = arrayOf("Basic realm=\"r\"", "Digest realm=\"r\", nonce=\"n1\", qop=\"auth\"")
        server.enqueue(unauthorized(*both))
        server.enqueue(unauthorized(*both))
        server.enqueue(response(207))

        val result = client.detectScheme(url(), "u", "p", allowHttp = true)

        assertEquals(WebDavResult.Ok(AuthScheme.Digest), result)
        assertNull(server.takeRequest().headers["Authorization"])
        assertNull(server.takeRequest().headers["Authorization"])
        val header = server.takeRequest().headers["Authorization"]!!
        assertTrue(header, header.startsWith("Digest username=\"u\", realm=\"r\", nonce=\"n1\""))
        assertTrue(header, header.contains("qop=auth, nc=00000001"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun triesEachOfferedSchemeOnceAndReportsWrongCredentials() = runBlocking {
        val both = arrayOf("Basic realm=\"r\"", "Digest realm=\"r\", nonce=\"n1\", qop=\"auth\"")
        repeat(4) { server.enqueue(unauthorized(*both)) }

        val result = client.detectScheme(url(), "u", "wrong", allowHttp = true)

        val error = (result as WebDavResult.Err).error as WebDavError.Unauthorized
        assertEquals(UnauthorizedKind.Generic, error.kind())
        // запрос без пароля, проба Digest, Digest с паролем, Basic с паролем
        assertEquals(4, server.requestCount)
    }

    @Test
    fun rejectsServerThatOffersOnlyUnsupportedSchemesWithoutSendingPassword() = runBlocking {
        server.enqueue(unauthorized("Negotiate"))

        val result = client.detectScheme(url(), "u", "p", allowHttp = true)

        val error = (result as WebDavResult.Err).error as WebDavError.Unauthorized
        assertEquals(UnauthorizedKind.Unsupported, error.kind())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anonymousServerFallsBackToBasic() = runBlocking {
        server.enqueue(response(207))
        server.enqueue(response(207))

        val result = client.detectScheme(url(), "u", "p", allowHttp = true)

        assertEquals(WebDavResult.Ok(AuthScheme.Basic), result)
    }

    @Test
    fun digestRetriesWithTheFreshNonceWhenTheOldOneIsStale() = runBlocking {
        server.enqueue(unauthorized("Digest realm=\"r\", nonce=\"n1\", qop=\"auth\""))
        server.enqueue(unauthorized("Digest realm=\"r\", nonce=\"n2\", qop=\"auth\", stale=true"))
        server.enqueue(response(207))

        val digest = client.authenticated("u", "p", allowHttp = true, scheme = AuthScheme.Digest)
        val result = digest.propfind(url(), 0)

        assertTrue(result is WebDavResult.Ok)
        server.takeRequest()
        assertTrue(server.takeRequest().headers["Authorization"]!!.contains("nonce=\"n1\""))
        assertTrue(server.takeRequest().headers["Authorization"]!!.contains("nonce=\"n2\""))
    }

    @Test
    fun digestWithWrongPasswordIsNotRepeated() = runBlocking {
        server.enqueue(unauthorized("Digest realm=\"r\", nonce=\"n1\", qop=\"auth\""))
        server.enqueue(unauthorized("Digest realm=\"r\", nonce=\"n1\", qop=\"auth\""))

        val digest = client.authenticated("u", "bad", allowHttp = true, scheme = AuthScheme.Digest)
        val result = digest.propfind(url(), 0)

        assertFalse(result is WebDavResult.Ok)
        assertEquals(2, server.requestCount)
    }

    private fun url() = server.url("/webdav/").toString()

    private fun response(code: Int) = MockResponse.Builder().code(code).body("<multistatus></multistatus>").build()

    private fun unauthorized(vararg challenges: String): MockResponse {
        val builder = MockResponse.Builder().code(401).body("")
        challenges.forEach { builder.addHeader("WWW-Authenticate", it) }
        return builder.build()
    }
}
