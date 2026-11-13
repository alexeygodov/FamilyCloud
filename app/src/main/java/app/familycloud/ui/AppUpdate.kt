package app.familycloud.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.familycloud.R
import app.familycloud.data.update.UpdateFailure
import app.familycloud.data.update.UpdateProblem
import app.familycloud.data.update.UpdateRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class UpdateUiState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val downloaded: Long = 0,
    val total: Long = 0,
    val ready: File? = null,
    val message: Int = R.string.update_checking,
)

internal class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UpdateRepository(application)
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun check() {
        if (job?.isActive == true) return
        mutableState.value = UpdateUiState(visible = true, busy = true)
        job = viewModelScope.launch {
            try {
                val file = repository.check { done, total ->
                    ensureActive()
                    mutableState.value = UpdateUiState(visible = true, busy = true,
                        downloaded = done, total = total, message = R.string.update_downloading)
                }
                mutableState.value = UpdateUiState(visible = true, ready = file,
                    message = if (file == null) R.string.update_current else R.string.update_ready)
            } catch (error: CancellationException) {
                throw error
            } catch (error: UpdateFailure) {
                fail(error.problem)
            }
        }
    }

    fun install(launch: (Uri) -> Unit) {
        val file = state.value.ready ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutableState.value = state.value.copy(busy = true)
            try {
                val uri = repository.prepareInstall(file)
                mutableState.value = state.value.copy(busy = false)
                launch(uri)
            } catch (error: CancellationException) {
                throw error
            } catch (error: UpdateFailure) {
                repository.cleanup()
                fail(error.problem)
            } catch (_: Exception) {
                fail(UpdateProblem.Install)
            }
        }
    }

    fun installUnavailable() = fail(UpdateProblem.Install)

    private fun fail(problem: UpdateProblem) {
        mutableState.value = UpdateUiState(visible = true, message = when (problem) {
            UpdateProblem.Network -> R.string.update_network_error
            UpdateProblem.Release -> R.string.update_release_error
            UpdateProblem.Asset -> R.string.update_asset_error
            UpdateProblem.InvalidApk -> R.string.update_apk_error
            UpdateProblem.Identity -> R.string.update_identity_error
            UpdateProblem.Install -> R.string.update_install_error
        })
    }

    fun dismiss() {
        val previous = job
        previous?.cancel()
        mutableState.value = UpdateUiState()
        job = viewModelScope.launch {
            previous?.join()
            repository.cleanup()
        }
    }
}

@Composable
internal fun AppUpdateButton(model: UpdateViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.dismiss()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Require another explicit tap; never start an installer automatically after settings.
    }
    TextButton(onClick = model::check) { Text(stringResource(R.string.update_button)) }
    if (state.visible) {
        AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(stringResource(R.string.update_title)) },
            text = {
                Column {
                    Text(stringResource(state.message))
                    if (state.busy) {
                        if (state.total > 0) {
                            LinearProgressIndicator(progress = { state.downloaded.toFloat() / state.total })
                            Text(stringResource(R.string.update_progress, state.downloaded / 1024, state.total / 1024))
                        } else LinearProgressIndicator()
                    }
                }
            },
            confirmButton = {
                if (state.ready != null && !state.busy) {
                    TextButton(onClick = {
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            try {
                                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")))
                            } catch (_: Exception) { model.installUnavailable() }
                        } else model.install { uri ->
                            installer.launch(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = ClipData.newRawUri("APK", uri)
                            })
                        }
                    }) { Text(stringResource(R.string.update_install)) }
                }
            },
            dismissButton = {
                TextButton(onClick = model::dismiss) {
                    Text(stringResource(if (state.busy) R.string.update_cancel else R.string.update_close))
                }
            },
        )
    }
}
