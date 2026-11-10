package app.familycloud.data.webdav

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.familycloud.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class WebDavRepository(
    private val client: WebDavClient,
    private val settings: SettingsRepository,
    private val urls: UrlBuilder,
) {
    suspend fun test(baseUrl: String, login: String, password: String, allowHttp: Boolean): WebDavResult<Unit> {
        val normalized = urls.normalizeBase(baseUrl) ?: return WebDavResult.Err(WebDavError.InvalidUrl)
        if (!urls.allowsScheme(normalized, allowHttp)) return WebDavResult.Err(WebDavError.InvalidUrl)
        val next = app.familycloud.data.settings.SavedConnection(normalized, login, password, allowHttp)
        val listed = client.authenticated(login, password, allowHttp).propfind(normalized, depth = 0)
        if (listed is WebDavResult.Err) return listed
        settings.persist(next)
        return WebDavResult.Ok(Unit)
    }

    suspend fun list(relativeFolder: String): WebDavResult<List<RemoteEntry>> {
        val base = settings.snapshot()?.baseUrl ?: return WebDavResult.Err(WebDavError.InvalidUrl)
        val url = folderUrl(relativeFolder) ?: return WebDavResult.Err(WebDavError.InvalidUrl)
        return client.propfind(url, depth = 1, baseUrl = base)
    }

    suspend fun createFolder(relativeFolder: String, name: String): WebDavResult<Unit> {
        val url = childUrl(relativeFolder, name, folderChild = true) ?: return WebDavResult.Err(WebDavError.InvalidUrl)
        return client.mkcol(url)
    }

    suspend fun delete(relativePath: String): WebDavResult<Unit> {
        val url = fileUrl(relativePath) ?: return WebDavResult.Err(WebDavError.InvalidUrl)
        return client.delete(url)
    }

    suspend fun move(fromRelative: String, toRelative: String): WebDavResult<Unit> {
        val from = fileUrl(fromRelative) ?: return WebDavResult.Err(WebDavError.InvalidUrl)
        val to = fileUrl(toRelative) ?: return WebDavResult.Err(WebDavError.InvalidUrl)
        return client.move(from, to)
    }

    suspend fun copy(fromRelative: String, toRelative: String): WebDavResult<Unit> {
        val from = fileUrl(fromRelative) ?: return WebDavResult.Err(WebDavError.InvalidUrl)
        val to = fileUrl(toRelative) ?: return WebDavResult.Err(WebDavError.InvalidUrl)
        return client.copy(from, to)
    }

    fun folderUrl(relativeFolder: String): String? {
        val base = settings.snapshot()?.baseUrl ?: return null
        val folder = if (relativeFolder.isEmpty() || relativeFolder.endsWith("/")) relativeFolder else "$relativeFolder/"
        return urls.resolve(base, folder)
    }

    fun fileUrl(relativePath: String): String? {
        val base = settings.snapshot()?.baseUrl ?: return null
        return urls.resolve(base, relativePath)
    }

    private fun childUrl(folder: String, name: String, folderChild: Boolean): String? {
        val relative = folder.trim('/')
        val path = buildString {
            if (relative.isNotEmpty()) append(relative).append('/')
            append(name.trim('/'))
            if (folderChild) append('/')
        }
        return fileUrl(path)
    }
}

class LocalFiles(private val context: Context, private val client: WebDavClient) {
    suspend fun saveToDownloads(url: String, displayName: String): WebDavResult<Unit> = withContext(Dispatchers.IO) {
        val opened = client.open(url)
        if (opened is WebDavResult.Err) return@withContext opened
        val stream = (opened as WebDavResult.Ok).value
        var created: Uri? = null
        try {
            val mime = mimeOf(displayName)
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FamilyCloud")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext WebDavResult.Err(WebDavError.Network)
                created = uri
                resolver.openOutputStream(uri)?.use { output -> stream.stream.copyTo(output) }
                    ?: return@withContext WebDavResult.Err(WebDavError.Network)
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "FamilyCloud",
                )
                if (!dir.exists() && !dir.mkdirs()) return@withContext WebDavResult.Err(WebDavError.Network)
                val file = File(dir, displayName)
                file.outputStream().use { output -> stream.stream.copyTo(output) }
            }
            WebDavResult.Ok(Unit)
        } catch (error: CancellationException) {
            created?.let { context.contentResolver.delete(it, null, null) }
            throw error
        } catch (_: Exception) {
            created?.let { context.contentResolver.delete(it, null, null) }
            WebDavResult.Err(WebDavError.Network)
        } finally {
            stream.close()
        }
    }

    suspend fun cacheForView(url: String, displayName: String): WebDavResult<Uri> = withContext(Dispatchers.IO) {
        val opened = client.open(url)
        if (opened is WebDavResult.Err) return@withContext opened
        val stream = (opened as WebDavResult.Ok).value
        try {
            val dir = File(context.cacheDir, "open").apply { mkdirs() }
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(20)?.forEach { it.delete() }
            val safe = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val file = File(dir, "${System.nanoTime()}_$safe")
            file.outputStream().use { output -> stream.stream.copyTo(output) }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            WebDavResult.Ok(uri)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            WebDavResult.Err(WebDavError.Network)
        } finally {
            stream.close()
        }
    }

    fun mimeOf(name: String): String {
        val extension = name.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"
    }
}

fun kindOf(name: String): FileKind {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp" -> FileKind.Image
        "mp4", "mkv", "webm", "mov", "3gp" -> FileKind.Video
        else -> FileKind.Other
    }
}

enum class FileKind { Image, Video, Other }
