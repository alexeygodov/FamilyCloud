package app.familycloud.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class CheckedUpdate(val apk: File?, val versionName: String)

/** Independent of WebDAV: no authenticators, cookies, credentials, or shared interceptors. */
internal class UpdateRepository(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val directory = File(context.cacheDir, "updates")
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun check(onProgress: (Long, Long) -> Unit): CheckedUpdate = withContext(io) {
        cleanupFiles()
        directory.mkdirs()
        val apk = File(directory, "update.apk")
        var keep = false
        try {
            val release = request(UpdatePolicy.RELEASE_API) { response ->
                if (!response.isSuccessful) throw UpdateFailure(UpdateProblem.Release)
                val body = response.body
                if (body.contentLength() > MAX_METADATA_BYTES) throw UpdateFailure(UpdateProblem.Release)
                val bytes = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_METADATA_BYTES) throw UpdateFailure(UpdateProblem.Release)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                JSONObject(String(bytes, Charsets.UTF_8))
            }
            if (release.getString("tag_name") != "latest" || release.optBoolean("draft")) {
                throw UpdateFailure(UpdateProblem.Release)
            }
            val announced = UpdatePolicy.versionFromNotes(release.optString("body"))
            val installed = installedVersion()
            if (!UpdatePolicy.isNewer(installed.name, installed.code, announced)) {
                return@withContext CheckedUpdate(null, UpdatePolicy.canonical(installed.name))
            }
            announcedVersion = announced
            val assets = release.getJSONArray("assets")
            val asset = UpdatePolicy.selectAsset((0 until assets.length()).map { index ->
                val value = assets.getJSONObject(index)
                ReleaseAsset(value.getString("name"), value.getString("browser_download_url"),
                    value.getLong("size"), value.optString("digest").takeIf { it.isNotBlank() && it != "null" })
            }, context.packageName.endsWith(".debug"))
            request(asset.url) { response ->
                if (!response.isSuccessful) throw UpdateFailure(UpdateProblem.Network)
                val body = response.body
                if (body.contentLength() != -1L && body.contentLength() != asset.size) {
                    throw UpdateFailure(UpdateProblem.InvalidApk)
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                onProgress(0, asset.size)
                body.byteStream().use { input ->
                    apk.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var reported = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > asset.size || downloaded > UpdatePolicy.MAX_APK_BYTES) {
                                throw UpdateFailure(UpdateProblem.InvalidApk)
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            if (downloaded - reported >= 256 * 1024 || downloaded == asset.size) {
                                reported = downloaded
                                onProgress(downloaded, asset.size)
                            }
                        }
                    }
                }
                if (downloaded != asset.size || (asset.digest != null &&
                        !asset.digest.substringAfter(":").equals(hex(digest.digest()), ignoreCase = true))) {
                    throw UpdateFailure(UpdateProblem.InvalidApk)
                }
            }
            validate(apk, announced)
            keep = true
            CheckedUpdate(apk, announced.name)
        } catch (error: CancellationException) {
            throw error
        } catch (error: UpdateFailure) {
            throw error
        } catch (_: IOException) {
            throw UpdateFailure(UpdateProblem.Network)
        } catch (_: Exception) {
            throw UpdateFailure(UpdateProblem.InvalidApk)
        } finally {
            if (!keep) apk.delete()
        }
    }

    /** Re-read package, version and certificates immediately before handing off to Android. */
    suspend fun prepareInstall(apk: File): Uri = withContext(io) {
        val announced = announcedVersion ?: throw UpdateFailure(UpdateProblem.InvalidApk)
        if (!apk.isFile || apk.length() !in 1..UpdatePolicy.MAX_APK_BYTES) {
            throw UpdateFailure(UpdateProblem.InvalidApk)
        }
        validate(apk, announced)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    }

    suspend fun cleanup() = withContext(io) { cleanupFiles() }

    private fun cleanupFiles() {
        directory.listFiles()?.forEach { it.delete() }
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(): AppVersion {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val name = info.versionName ?: throw UpdateFailure(UpdateProblem.InvalidApk)
        return AppVersion(name, version(info))
    }

    @Suppress("DEPRECATION")
    private fun validate(apk: File, announced: AppVersion) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags)
        val downloaded = manager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw UpdateFailure(UpdateProblem.InvalidApk)
        downloaded.applicationInfo?.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }
        val downloadedSigners = signers(downloaded)
        if (downloadedSigners.isEmpty()) throw UpdateFailure(UpdateProblem.InvalidApk)
        val actualName = downloaded.versionName ?: throw UpdateFailure(UpdateProblem.InvalidApk)
        if (!UpdatePolicy.matches(actualName, version(downloaded), announced)) {
            throw UpdateFailure(UpdateProblem.InvalidApk)
        }
        UpdatePolicy.validateIdentity(installed.packageName, downloaded.packageName, signers(installed), downloadedSigners)
    }

    @Suppress("DEPRECATION")
    private fun version(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private suspend fun <T> request(url: String, consume: suspend (Response) -> T): T {
        var next = requestFor(url)
        repeat(6) {
            requireAllowed(next.url)
            val request = next
            val result = coroutineScope {
                val call = client.newCall(request)
                val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        if (response.code in listOf(301, 302, 303, 307, 308)) {
                            val location = response.header("Location") ?: throw UpdateFailure(UpdateProblem.Network)
                            val target = response.request.url.resolve(location) ?: throw UpdateFailure(UpdateProblem.Asset)
                            requireAllowed(target)
                            next = requestFor(target.toString())
                            Fetch.Redirect
                        } else {
                            Fetch.Completed(consume(response))
                        }
                    }
                } finally {
                    cancellation.cancel()
                }
            }
            when (result) {
                Fetch.Redirect -> Unit
                is Fetch.Completed -> return result.value
            }
        }
        throw UpdateFailure(UpdateProblem.Network)
    }

    private fun requestFor(url: String): Request {
        val parsed = url.toHttpUrlOrNull() ?: throw UpdateFailure(UpdateProblem.Asset)
        val builder = Request.Builder().url(parsed).header("User-Agent", "FamilyCloud-Android-Updater")
        if (parsed.host == "api.github.com") builder.header("Accept", "application/vnd.github+json")
        return builder.build()
    }

    private fun requireAllowed(url: HttpUrl) {
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.host !in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com",
                "objects.githubusercontent.com", "github-releases.githubusercontent.com")) {
            throw UpdateFailure(UpdateProblem.Asset)
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private var announcedVersion: AppVersion? = null

    private sealed interface Fetch<out T> {
        data object Redirect : Fetch<Nothing>
        data class Completed<T>(val value: T) : Fetch<T>
    }

    private companion object { const val MAX_METADATA_BYTES = 1024 * 1024 }
}
