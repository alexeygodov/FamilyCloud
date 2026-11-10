package app.familycloud.data.webdav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavErrorTest {
    @Test
    fun retriesOnlyNetworkTimeoutSslAndServerErrors() {
        assertTrue(WebDavError.Network.retries())
        assertTrue(WebDavError.Timeout.retries())
        assertTrue(WebDavError.Ssl.retries())
        assertTrue(WebDavError.Http(503).retries())
        assertFalse(WebDavError.Unauthorized.retries())
        assertFalse(WebDavError.Forbidden.retries())
        assertFalse(WebDavError.InsufficientStorage.retries())
        assertFalse(WebDavError.Http(404).retries())
    }
}
