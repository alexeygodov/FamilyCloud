package app.familycloud.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

data class SavedConnection(
    val baseUrl: String,
    val login: String,
    val password: String,
    val allowHttp: Boolean,
) {
    override fun toString(): String = "SavedConnection(baseUrl=$baseUrl, login=$login, allowHttp=$allowHttp)"
}

private val Context.connectionStore by preferencesDataStore(name = "connection")

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.connectionStore
    private val secure: SharedPreferences = createSecurePrefs(context.applicationContext)
    private val state = MutableStateFlow<SavedConnection?>(null)
    val connection: StateFlow<SavedConnection?> = state

    @Volatile
    private var attempt: SavedConnection? = null

    @Volatile
    private var publishedGeneration = 0

    suspend fun load() {
        val seen = publishedGeneration
        val prefs = dataStore.data.first()
        val url = prefs[URL]
        val login = prefs[LOGIN]
        val allowHttp = prefs[ALLOW_HTTP] == true
        val password = secure.getString(PASSWORD, null)
        if (publishedGeneration != seen) return
        state.value = if (url.isNullOrBlank() || login.isNullOrBlank() || password.isNullOrEmpty()) {
            null
        } else {
            SavedConnection(url, login, password, allowHttp)
        }
    }

    suspend fun save(connection: SavedConnection) {
        persist(connection)
    }

    fun beginAttempt(connection: SavedConnection) {
        attempt = connection
    }

    fun endAttempt() {
        attempt = null
    }

    suspend fun persist(connection: SavedConnection) {
        publishedGeneration += 1
        dataStore.edit { prefs ->
            prefs[URL] = connection.baseUrl
            prefs[LOGIN] = connection.login
            prefs[ALLOW_HTTP] = connection.allowHttp
        }
        secure.edit().putString(PASSWORD, connection.password).apply()
        state.value = connection
    }

    fun snapshot(): SavedConnection? = attempt ?: state.value

    private fun createSecurePrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val SECURE_FILE = "connection_secure"
        const val PASSWORD = "password"
        val URL = stringPreferencesKey("url")
        val LOGIN = stringPreferencesKey("login")
        val ALLOW_HTTP = booleanPreferencesKey("allow_http")
    }
}
