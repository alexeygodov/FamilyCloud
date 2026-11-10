package app.familycloud.data.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebDavClient(
    private val http: OkHttpClient = defaultClient(),
) {
    suspend fun propfind(url: String, depth: Int, baseUrl: String = url): WebDavResult<List<RemoteEntry>> = call(url) { target ->
        val request = Request.Builder()
            .url(target)
            .header("Depth", depth.toString())
            .header("Content-Type", XML)
            .method("PROPFIND", PROPFIND_BODY)
            .build()
        request.await().use { response ->
            if (response.code == 207) {
                val body = response.body.string()
                WebDavResult.Ok(PropfindParser().parse(body, baseUrl, target))
            } else {
                WebDavResult.Err(codeToError(response.code))
            }
        }
    }

    fun authenticated(login: String, password: String, allowHttp: Boolean): WebDavClient {
        val header = Credentials.basic(login, password, StandardCharsets.UTF_8)
        val builder = http.newBuilder()
        builder.interceptors().clear()
        val client = builder
            .authenticator(Authenticator.NONE)
            .addInterceptor { chain ->
                if (chain.request().url.scheme == "http" && !allowHttp) throw HttpDisabledException()
                chain.proceed(chain.request().newBuilder().header("Authorization", header).build())
            }
            .build()
        return WebDavClient(client)
    }

    suspend fun mkcol(url: String): WebDavResult<Unit> = simple("MKCOL", url, success = setOf(201, 204))

    suspend fun delete(url: String): WebDavResult<Unit> = simple("DELETE", url, success = setOf(200, 204, 404))

    suspend fun move(fromUrl: String, toUrl: String, overwrite: Boolean = false): WebDavResult<Unit> {
        return relocate("MOVE", fromUrl, toUrl, overwrite)
    }

    suspend fun copy(fromUrl: String, toUrl: String, overwrite: Boolean = false): WebDavResult<Unit> {
        return relocate("COPY", fromUrl, toUrl, overwrite)
    }

    suspend fun put(
        url: String,
        source: InputStream,
        contentLength: Long,
        onProgress: (sent: Long) -> Unit = {},
    ): WebDavResult<Unit> = call(url) { target ->
        val request = Request.Builder()
            .url(target)
            .put(StreamingBody(source, contentLength, onProgress))
            .build()
        request.await().use { response ->
            if (response.code in setOf(200, 201, 204)) {
                WebDavResult.Ok(Unit)
            } else {
                WebDavResult.Err(codeToError(response.code))
            }
        }
    }

    suspend fun open(url: String): WebDavResult<ByteStream> = call(url) { target ->
        val response = Request.Builder().url(target).get().build().await()
        if (response.isSuccessful) {
            val body = response.body
            WebDavResult.Ok(ByteStream(body.byteStream(), body.contentLength(), response))
        } else {
            response.close()
            WebDavResult.Err(codeToError(response.code))
        }
    }

    private suspend fun simple(method: String, url: String, success: Set<Int>): WebDavResult<Unit> {
        return call(url) { target ->
            val request = Request.Builder().url(target).method(method, null).build()
            request.await().use { response ->
                if (response.code in success) WebDavResult.Ok(Unit) else WebDavResult.Err(codeToError(response.code))
            }
        }
    }

    private suspend fun relocate(
        method: String,
        fromUrl: String,
        toUrl: String,
        overwrite: Boolean,
    ): WebDavResult<Unit> = call(fromUrl) { target ->
        val request = Request.Builder()
            .url(target)
            .header("Destination", toUrl)
            .header("Overwrite", if (overwrite) "T" else "F")
            .method(method, null)
            .build()
        request.await().use { response ->
            if (response.code in setOf(201, 204)) {
                WebDavResult.Ok(Unit)
            } else {
                WebDavResult.Err(codeToError(response.code))
            }
        }
    }

    private suspend fun <T> call(url: String, block: suspend (String) -> WebDavResult<T>): WebDavResult<T> {
        if (url.toHttpUrlOrNull() == null) return WebDavResult.Err(WebDavError.InvalidUrl)
        return withContext(Dispatchers.IO) {
            try {
                block(url)
            } catch (error: CancellationException) {
                throw error
            } catch (_: HttpDisabledException) {
                WebDavResult.Err(WebDavError.HttpDisabled)
            } catch (error: SSLException) {
                WebDavResult.Err(WebDavError.Ssl)
            } catch (_: java.net.SocketTimeoutException) {
                WebDavResult.Err(WebDavError.Timeout)
            } catch (_: java.io.IOException) {
                WebDavResult.Err(WebDavError.Network)
            }
        }
    }

    private fun codeToError(code: Int): WebDavError = when (code) {
        401 -> WebDavError.Unauthorized
        403 -> WebDavError.Forbidden
        404 -> WebDavError.NotFound
        405 -> WebDavError.MethodNotAllowed
        409, 412 -> WebDavError.Conflict
        423 -> WebDavError.Locked
        507 -> WebDavError.InsufficientStorage
        else -> WebDavError.Http(code)
    }

    private class StreamingBody(
        private val input: InputStream,
        private val length: Long,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = OCTET
        override fun contentLength() = length
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            input.source().use { source ->
                var sent = 0L
                while (true) {
                    val read = source.read(sink.buffer, BUFFER)
                    if (read == -1L) break
                    sink.emitCompleteSegments()
                    sent += read
                    onProgress(sent)
                }
            }
        }
    }

    private suspend fun Request.await(): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(this@await)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }
        })
    }

    companion object {
        private const val XML = "application/xml; charset=utf-8"
        private const val BUFFER = 64L * 1024L
        private val OCTET = "application/octet-stream".toMediaType()
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:getcontentlength/>
                <d:getlastmodified/>
                <d:resourcetype/>
                <d:getcontenttype/>
              </d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody(XML.toMediaType())

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

class ByteStream(
    val stream: InputStream,
    val length: Long,
    private val closeable: AutoCloseable,
) : AutoCloseable {
    override fun close() {
        stream.close()
        closeable.close()
    }
}
