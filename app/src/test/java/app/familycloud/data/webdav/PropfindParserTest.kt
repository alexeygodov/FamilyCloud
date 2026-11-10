package app.familycloud.data.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PropfindParserTest {
    private val parser = PropfindParser()
    private val base = "https://example.keenetic.link/webdav/"

    @Test
    fun emptyBodyIsEmptyList() {
        assertTrue(parser.parse("", base).isEmpty())
        assertTrue(parser.parse("<D:multistatus xmlns:D=\"DAV:\"></D:multistatus>", base).isEmpty())
    }

    @Test
    fun parsesPrefixedHrefFoldersFilesAndDropsSelf() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/webdav/</D:href>
                <D:propstat>
                  <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/webdav/%D0%A4%D0%BE%D1%82%D0%BE/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:displayname>Фото</D:displayname>
                    <D:resourcetype><D:collection/></D:resourcetype>
                    <D:getlastmodified>Mon, 10 Nov 2025 12:00:00 GMT</D:getlastmodified>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>https://example.keenetic.link/webdav/my%20file.txt</D:href>
                <D:propstat>
                  <D:prop>
                    <D:getcontentlength>42</D:getcontentlength>
                    <D:getlastmodified>Tue, 11 Nov 2025 08:30:00 GMT</D:getlastmodified>
                    <D:resourcetype/>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val entries = parser.parse(xml, base)
        assertEquals(listOf("Фото/", "my file.txt"), entries.map { it.relativePath })
        assertEquals(true, entries[0].isFolder)
        assertEquals("Фото", entries[0].name)
        assertEquals(null, entries[0].size)
        assertEquals(false, entries[1].isFolder)
        assertEquals(42L, entries[1].size)
        assertTrue(entries[1].modifiedEpochMs != null)
    }

    @Test
    fun acceptsLowerAndNoPrefix() {
        val lower = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/webdav/dir/</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val plain = """
            <multistatus>
              <response>
                <href>plain.txt</href>
                <propstat>
                  <prop><getcontentlength>7</getcontentlength><resourcetype></resourcetype></prop>
                  <status>HTTP/1.1 200 OK</status>
                </propstat>
              </response>
            </multistatus>
        """.trimIndent()
        assertEquals("dir/", parser.parse(lower, base).single().relativePath)
        assertEquals("plain.txt", parser.parse(plain, base).single().relativePath)
    }

    @Test
    fun nestedFolderKeepsPathFromWebDavRoot() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/webdav/Photos/</D:href>
                <D:propstat>
                  <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/webdav/Photos/a.jpg</D:href>
                <D:propstat>
                  <D:prop><D:displayname>Не то имя</D:displayname><D:getcontentlength>3</D:getcontentlength></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/webdav/Photos/Sub/</D:href>
                <D:propstat>
                  <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val entries = parser.parse(xml, base, base + "Photos/")
        assertEquals(listOf("Photos/a.jpg", "Photos/Sub/"), entries.map { it.relativePath })
        assertEquals("a.jpg", entries[0].name)
        assertEquals("Не то имя", entries[0].label)
        assertEquals("Sub", entries[1].name)
    }
}
