package app.familycloud.data.webdav

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Нормализует базовый URL и кодирует пути по сегментам.
 * UI не склеивает адреса строками.
 */
class UrlBuilder {
    fun normalizeBase(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        val parsed = lenient(withSlash) ?: return null
        if (parsed.scheme != "https" && parsed.scheme != "http") return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        val path = parsed.encodedPath.let { path -> if (path.endsWith("/")) path else "$path/" }
        return parsed.newBuilder().encodedPath(path).query(null).fragment(null).build().toString()
    }

    fun allowsScheme(baseUrl: String, allowHttp: Boolean): Boolean {
        val scheme = lenient(baseUrl)?.scheme ?: return false
        return scheme == "https" || (scheme == "http" && allowHttp)
    }

    fun resolve(baseUrl: String, relativePath: String): String? {
        val base = lenient(baseUrl) ?: return null
        val folder = relativePath.endsWith("/")
        val builder = base.newBuilder()
        relativePath.trim('/').split('/').filter { it.isNotEmpty() }.forEach { segment ->
            builder.addEncodedPathSegment(encodeSegment(segment))
        }
        val built = builder.build()
        if (!folder && relativePath.isNotEmpty() && !relativePath.endsWith("/")) {
            return built.toString()
        }
        val path = built.encodedPath.let { path -> if (path.endsWith("/")) path else "$path/" }
        return built.newBuilder().encodedPath(path).build().toString()
    }

    fun parentRelative(relativePath: String): String? {
        val trimmed = relativePath.trim('/')
        if (trimmed.isEmpty()) return null
        val parent = trimmed.substringBeforeLast('/', missingDelimiterValue = "")
        return if (parent.isEmpty()) "" else "$parent/"
    }

    fun relativeOf(baseUrl: String, href: String): String? {
        val base = lenient(baseUrl) ?: return null
        val resolved = resolveHref(base, href) ?: return null
        if (!resolved.scheme.equals(base.scheme, ignoreCase = true) || resolved.host != base.host) {
            return null
        }
        val basePath = base.encodedPath
        val full = resolved.encodedPath
        if (!full.startsWith(basePath)) return null
        val encodedRest = full.removePrefix(basePath)
        val decoded = decodePath(encodedRest)
        return if (full.endsWith("/") && decoded.isNotEmpty() && !decoded.endsWith("/")) {
            "$decoded/"
        } else {
            decoded
        }
    }

    fun fileName(relativePath: String): String {
        return relativePath.trim('/').substringAfterLast('/')
    }

    private fun resolveHref(base: HttpUrl, href: String): HttpUrl? {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) return null
        lenient(trimmed)?.let { return it }
        return base.resolve(trimmed)
    }

    fun isSafeName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() &&
            trimmed != "." &&
            trimmed != ".." &&
            !trimmed.contains('/') &&
            !trimmed.contains('\\')
    }

    private fun lenient(value: String): HttpUrl? {
        value.toHttpUrlOrNull()?.let { return it }
        val encoded = buildString {
            var index = 0
            while (index < value.length) {
                val code = value.codePointAt(index)
                index += Character.charCount(code)
                if (code < 128 && code != ' '.code) {
                    append(code.toChar())
                } else {
                    append(percent(String(Character.toChars(code))))
                }
            }
        }
        return encoded.toHttpUrlOrNull()
    }

    private fun encodeSegment(segment: String): String {
        return buildString {
            var index = 0
            while (index < segment.length) {
                val code = segment.codePointAt(index)
                index += Character.charCount(code)
                if (code < 128) {
                    val char = code.toChar()
                    if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' || char == '~') {
                        append(char)
                    } else {
                        append(percent(char.toString()))
                    }
                } else {
                    append(percent(String(Character.toChars(code))))
                }
            }
        }
    }

    private fun percent(text: String): String {
        return URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    }

    private fun decodePath(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return encoded.split('/').joinToString("/") { segment ->
            URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
        }
    }
}
