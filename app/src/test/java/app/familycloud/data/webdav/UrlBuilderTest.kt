package app.familycloud.data.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlBuilderTest {
    private val urls = UrlBuilder()
    private val base = "https://example.keenetic.link/webdav/"

    @Test
    fun normalizesTrailingSlashAndDropsUserInfoQuery() {
        assertEquals(base, urls.normalizeBase("https://example.keenetic.link/webdav"))
        assertEquals(base, urls.normalizeBase("  https://example.keenetic.link/webdav/  "))
        assertNull(urls.normalizeBase("https://user:secret@example.keenetic.link/webdav/"))
        assertNull(urls.normalizeBase("ftp://example.keenetic.link/webdav/"))
        assertNull(urls.normalizeBase("not a url"))
    }

    @Test
    fun encodesSegments() {
        val file = urls.resolve(base, "Фото/my file#1%+.txt")
        assertEquals(
            "https://example.keenetic.link/webdav/%D0%A4%D0%BE%D1%82%D0%BE/my%20file%231%25%2B.txt",
            file,
        )
        val folder = urls.resolve(base, "Фото/")
        assertEquals("https://example.keenetic.link/webdav/%D0%A4%D0%BE%D1%82%D0%BE/", folder)
    }

    @Test
    fun relativeHrefFormats() {
        assertEquals("", urls.relativeOf(base, "/webdav/"))
        assertEquals("", urls.relativeOf(base, "https://example.keenetic.link/webdav/"))
        assertEquals("Фото/", urls.relativeOf(base, "/webdav/%D0%A4%D0%BE%D1%82%D0%BE/"))
        assertEquals("Фото/", urls.relativeOf(base, "https://example.keenetic.link/webdav/Фото/"))
        assertEquals("my file.txt", urls.relativeOf(base, "my%20file.txt"))
        assertEquals("a+b.txt", urls.relativeOf(base, "a%2Bb.txt"))
        assertEquals("dir/", urls.relativeOf(base, "dir/"))
        assertNull(urls.relativeOf(base, "https://other.example/webdav/a"))
    }

    @Test
    fun encodesEmojiAsUtf8NotQuestionMarks() {
        val file = urls.resolve(base, "😀 фото.jpg")
        assertEquals(true, file!!.contains("%F0%9F%98%80"))
        assertEquals(false, file.contains("%3F"))
        assertEquals(false, urls.isSafeName(".."))
        assertEquals(false, urls.isSafeName("a/b"))
        assertEquals(true, urls.isSafeName("фото.jpg"))
    }

    @Test
    fun httpAllowedOnlyWhenRequested() {
        val http = "http://example.keenetic.link/webdav/"
        assertEquals(false, urls.allowsScheme(http, allowHttp = false))
        assertEquals(true, urls.allowsScheme(http, allowHttp = true))
        assertEquals(true, urls.allowsScheme(base, allowHttp = false))
    }
}
