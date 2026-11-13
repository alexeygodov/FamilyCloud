package app.familycloud.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.familycloud.R
import app.familycloud.data.settings.SettingsRepository
import app.familycloud.data.webdav.WebDavError
import app.familycloud.data.webdav.WebDavRepository
import app.familycloud.data.webdav.WebDavResult
import app.familycloud.sync.errorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ConnectionViewModel(
    app: Application,
    private val repository: WebDavRepository,
    settings: SettingsRepository,
) : AndroidViewModel(app) {
    val saved = settings.connection
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val attemptedUrl = MutableStateFlow<String?>(null)

    fun submit(url: String, login: String, password: String, allowHttp: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            val target = repository.normalizedAddress(url)
            attemptedUrl.value = target
            if (url.trim().startsWith("http://", ignoreCase = true) && !allowHttp) {
                error.value = getApplication<Application>().getString(R.string.error_http_off)
                busy.value = false
                return@launch
            }
            when (val result = repository.test(url, login.trim(), password, allowHttp)) {
                is WebDavResult.Ok -> onDone()
                is WebDavResult.Err -> {
                    val app = getApplication<Application>()
                    error.value = if (result.error is WebDavError.Unauthorized) {
                        app.getString(R.string.error_unauthorized_short) + "\n" +
                            app.getString(R.string.error_lockout)
                    } else {
                        errorText(app, result.error)
                    }
                }
            }
            busy.value = false
        }
    }
}
