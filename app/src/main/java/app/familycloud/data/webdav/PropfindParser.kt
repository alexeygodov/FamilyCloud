package app.familycloud.data.webdav

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class PropfindParser(
    private val urls: UrlBuilder = UrlBuilder(),
) {
    fun parse(xml: String, requestUrl: String): List<RemoteEntry> {
        if (xml.isBlank()) return emptyList()
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        parser.setInput(StringReader(xml))
        val entries = mutableListOf<RemoteEntry>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.local() == "response") {
                val raw = parseResponse(parser)
                if (raw != null) {
                    val relative = urls.relativeOf(requestUrl, raw.href)
                    if (!relative.isNullOrEmpty() && relative != "/") {
                        val folder = raw.isFolder || relative.endsWith("/")
                        val name = raw.displayName?.takeIf { it.isNotBlank() } ?: urls.fileName(relative)
                        entries += RemoteEntry(
                            relativePath = relative,
                            name = name,
                            isFolder = folder,
                            size = if (folder) null else raw.size,
                            modifiedEpochMs = raw.modifiedEpochMs,
                        )
                    }
                }
            }
            event = parser.next()
        }
        return entries
    }

    private fun parseResponse(parser: XmlPullParser): RawResponse? {
        var href: String? = null
        var displayName: String? = null
        var isFolder = false
        var size: Long? = null
        var modified: Long? = null
        var sawOk = false
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return null
            if (event == XmlPullParser.END_TAG && parser.local() == "response") break
            if (event != XmlPullParser.START_TAG) continue
            when (parser.local()) {
                "href" -> if (href == null) href = parser.nextText().trim()
                "propstat" -> {
                    val stat = parsePropstat(parser)
                    if (stat.ok) {
                        sawOk = true
                        if (stat.displayName != null) displayName = stat.displayName
                        if (stat.isFolder) isFolder = true
                        if (stat.size != null) size = stat.size
                        if (stat.modified != null) modified = stat.modified
                    }
                }
            }
        }
        val location = href ?: return null
        if (!sawOk && !location.endsWith("/")) return null
        return RawResponse(location, displayName, isFolder || location.endsWith("/"), size, modified)
    }

    private fun parsePropstat(parser: XmlPullParser): PropStat {
        var displayName: String? = null
        var isFolder = false
        var size: Long? = null
        var modified: Long? = null
        var ok = false
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.local() == "propstat") break
            if (event != XmlPullParser.START_TAG) continue
            when (parser.local()) {
                "collection" -> isFolder = true
                "displayname" -> displayName = parser.nextText()
                "getcontentlength" -> size = parser.nextText().trim().toLongOrNull()
                "getlastmodified" -> modified = parseHttpDate(parser.nextText().trim())
                "status" -> ok = parser.nextText().contains(" 200 ")
            }
        }
        return PropStat(ok, displayName, isFolder, size, modified)
    }

    private fun parseHttpDate(value: String): Long? {
        if (value.isEmpty()) return null
        return try {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun XmlPullParser.local(): String = name.substringAfter(':').lowercase(Locale.US)

    private data class RawResponse(
        val href: String,
        val displayName: String?,
        val isFolder: Boolean,
        val size: Long?,
        val modifiedEpochMs: Long?,
    )

    private data class PropStat(
        val ok: Boolean,
        val displayName: String?,
        val isFolder: Boolean,
        val size: Long?,
        val modified: Long?,
    )
}
