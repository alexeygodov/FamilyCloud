package app.familycloud.data.webdav

import app.familycloud.data.settings.SavedConnection
import okhttp3.Authenticator
import okhttp3.Challenge
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Способы входа, которые умеет приложение. Какой подошёл — сохраняется в настройках подключения. */
enum class AuthScheme {
    Basic,
    Digest,
    ;

    companion object {
        fun parse(value: String?): AuthScheme = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Basic

        fun fromChallenge(name: String?): AuthScheme? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Digest-вызов сервера (RFC 2617 / RFC 7616, qop=auth или без qop). */
internal class DigestChallenge(
    val realm: String,
    val nonce: String,
    val opaque: String?,
    val algorithm: String,
    val useQop: Boolean,
    val stale: Boolean,
) {
    private val hashName: String get() = if (algorithm.startsWith("SHA-256")) "SHA-256" else "MD5"
    private val sessionVariant: Boolean get() = algorithm.endsWith("-SESS")

    fun response(method: String, uri: String, login: String, password: String, nc: String, cnonce: String): String {
        var ha1 = hash("$login:$realm:$password")
        if (sessionVariant) ha1 = hash("$ha1:$nonce:$cnonce")
        val ha2 = hash("$method:$uri")
        return if (useQop) {
            hash("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            hash("$ha1:$nonce:$ha2")
        }
    }

    fun header(method: String, uri: String, login: String, password: String, nc: String, cnonce: String): String {
        val digest = response(method, uri, login, password, nc, cnonce)
        return buildString {
            append("Digest username=\"").append(quote(login)).append('"')
            append(", realm=\"").append(quote(realm)).append('"')
            append(", nonce=\"").append(quote(nonce)).append('"')
            append(", uri=\"").append(quote(uri)).append('"')
            append(", algorithm=").append(wireAlgorithm(algorithm))
            append(", response=\"").append(digest).append('"')
            if (opaque != null) append(", opaque=\"").append(quote(opaque)).append('"')
            if (useQop) append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"')
        }
    }

    private fun hash(text: String): String {
        val bytes = MessageDigest.getInstance(hashName).digest(text.toByteArray(StandardCharsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (byte in bytes) append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
        }
    }

    private fun quote(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun wireAlgorithm(normalized: String): String = when (normalized) {
        "MD5-SESS" -> "MD5-sess"
        "SHA-256-SESS" -> "SHA-256-sess"
        else -> normalized
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
        private val SUPPORTED = listOf("SHA-256", "SHA-256-SESS", "MD5", "MD5-SESS")

        /** Лучший из поддерживаемых Digest-вызовов в ответе сервера или null. */
        fun pick(challenges: List<Challenge>): DigestChallenge? {
            return challenges
                .filter { it.scheme.equals("Digest", ignoreCase = true) }
                .mapNotNull { parse(it) }
                .minByOrNull { SUPPORTED.indexOf(it.algorithm) }
        }

        private fun parse(challenge: Challenge): DigestChallenge? {
            val params = challenge.authParams
            val nonce = params["nonce"] ?: return null
            val algorithm = (params["algorithm"] ?: "MD5").uppercase(Locale.ROOT)
            if (algorithm !in SUPPORTED) return null
            val qop = params["qop"]?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }.orEmpty()
            if (qop.isNotEmpty() && "auth" !in qop) return null
            return DigestChallenge(
                realm = params["realm"].orEmpty(),
                nonce = nonce,
                opaque = params["opaque"],
                algorithm = algorithm,
                useQop = qop.isNotEmpty(),
                stale = params["stale"].equals("true", ignoreCase = true),
            )
        }
    }
}

/**
 * Подставляет авторизацию по схеме, сохранённой в подключении: Basic — сразу, Digest — по вызову сервера
 * (первый вызов получаем отдельным запросом без пароля, чтобы не терять тело загрузки и не слать пароль зря).
 */
class SchemeAuth(private val source: () -> SavedConnection?) : Interceptor, Authenticator {
    private class Session(val login: String, val password: String, val challenge: DigestChallenge) {
        private val counter = AtomicInteger()

        fun matches(connection: SavedConnection) = login == connection.login && password == connection.password

        fun header(request: Request, random: SecureRandom): String {
            val query = request.url.encodedQuery?.let { "?$it" }.orEmpty()
            val nc = String.format(Locale.ROOT, "%08x", counter.incrementAndGet())
            val cnonce = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            return challenge.header(request.method, request.url.encodedPath + query, login, password, nc, cnonce)
        }
    }

    private object ProbeTag

    private val random = SecureRandom()

    @Volatile private var session: Session? = null

    @Volatile private var probeSkipUntil = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val saved = source()
        if (request.url.scheme == "http" && saved?.allowHttp != true) throw HttpDisabledException()
        if (saved == null) return chain.proceed(request)
        val header = when (saved.authScheme) {
            AuthScheme.Basic -> Credentials.basic(saved.login, saved.password, StandardCharsets.UTF_8)
            AuthScheme.Digest -> (session?.takeIf { it.matches(saved) } ?: probe(chain, request, saved))?.header(request, random)
        }
        val authed = if (header == null) request else request.newBuilder().header("Authorization", header).build()
        return chain.proceed(authed)
    }

    private fun probe(chain: Interceptor.Chain, request: Request, saved: SavedConnection): Session? {
        if (System.currentTimeMillis() < probeSkipUntil) return null
        val probeRequest = Request.Builder()
            .url(request.url)
            .header("Depth", "0")
            .method("PROPFIND", null)
            .tag(ProbeTag::class.java, ProbeTag)
            .build()
        val challenge = chain.proceed(probeRequest).use { DigestChallenge.pick(it.challenges()) }
        if (challenge == null) {
            probeSkipUntil = System.currentTimeMillis() + PROBE_PAUSE_MS
            return null
        }
        return Session(saved.login, saved.password, challenge).also { session = it }
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.tag(ProbeTag::class.java) != null) return null
        val saved = source() ?: return null
        if (saved.authScheme != AuthScheme.Digest) return null
        if (priorResponses(response) >= 2) return null
        val challenge = DigestChallenge.pick(response.challenges()) ?: return null
        val previous = response.request.header("Authorization")
        // Тот же nonce уже был с паролем и не «просрочен» — значит, пароль или логин неверны.
        if (previous != null && !challenge.stale && previous.contains("nonce=\"${challenge.nonce}\"")) return null
        val next = Session(saved.login, saved.password, challenge)
        session = next
        return response.request.newBuilder().header("Authorization", next.header(response.request, random)).build()
    }

    private fun priorResponses(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count += 1
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        const val PROBE_PAUSE_MS = 30_000L
    }
}
