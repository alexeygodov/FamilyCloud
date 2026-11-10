package app.familycloud

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.room.Room
import androidx.work.Configuration
import app.familycloud.data.db.AppDatabase
import app.familycloud.data.settings.SavedConnection
import app.familycloud.data.settings.SettingsRepository
import app.familycloud.data.webdav.LocalFiles
import app.familycloud.data.webdav.UrlBuilder
import app.familycloud.data.webdav.WebDavClient
import app.familycloud.data.webdav.WebDavRepository
import app.familycloud.sync.MediaScanner
import app.familycloud.sync.SyncRepository
import app.familycloud.sync.SyncScheduler
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okio.Path.Companion.toOkioPath
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class FamilyCloudApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scope.launch {
            container.settings.load()
            SyncScheduler.refresh(this@FamilyCloudApp, container.sync.pairs())
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return container.imageLoader
    }
}

class AppContainer(private val app: Application) {
    val settings = SettingsRepository(app)
    val urls = UrlBuilder()
    private val debug = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        // Keenetic на HTTP/2 отвечает 401 на PROPFIND, хотя тот же пароль по HTTP/1.1 принимается.
        .protocols(listOf(Protocol.HTTP_1_1))
        .authenticator(BasicAuthenticator(settings))
        .addInterceptor(AuthInterceptor(settings))
        .apply {
            if (debug) {
                addInterceptor(Interceptor { chain ->
                    val response = chain.proceed(chain.request())
                    Log.i("FamilyCloud", "${chain.request().method} ${response.code}")
                    response
                })
            }
        }
        .build()

    private val imageHttp: OkHttpClient = http.newBuilder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 3
            maxRequestsPerHost = 3
        })
        .build()

    val imageLoader: ImageLoader = ImageLoader.Builder(app)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp }))
        }
        .diskCache {
            DiskCache.Builder()
                .directory(app.cacheDir.resolve("images").toOkioPath())
                .maxSizeBytes(256L * 1024L * 1024L)
                .build()
        }
        .build()

    val database: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "familycloud.db").build()
    val webdav = WebDavClient(http)
    val repository = WebDavRepository(webdav, settings, urls)
    val localFiles = LocalFiles(app, webdav)
    val scanner = MediaScanner(app)
    val sync = SyncRepository(app, database, scanner, webdav, settings, urls)
}

private class AuthInterceptor(
    private val settings: SettingsRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val saved: SavedConnection? = settings.snapshot()
        if (request.url.scheme == "http" && saved?.allowHttp != true) {
            throw IOException("http-disabled")
        }
        val authed = if (saved == null) {
            request
        } else {
            request.newBuilder()
                .header("Authorization", Credentials.basic(saved.login, saved.password, StandardCharsets.UTF_8))
                .build()
        }
        return chain.proceed(authed)
    }
}

private class BasicAuthenticator(
    private val settings: SettingsRepository,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (priorResponses(response) >= 2) return null
        val saved = settings.snapshot() ?: return null
        val value = Credentials.basic(saved.login, saved.password, StandardCharsets.UTF_8)
        if (response.request.header("Authorization") == value) return null
        return response.request.newBuilder().header("Authorization", value).build()
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
}
