package app.familycloud.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import app.familycloud.FamilyCloudApp
import app.familycloud.ui.browser.BrowserViewModel
import app.familycloud.ui.settings.ConnectionViewModel
import app.familycloud.ui.sync.SyncViewModel
import app.familycloud.ui.viewer.ImageViewModel
import app.familycloud.ui.viewer.VideoViewModel

class AppViewModelFactory(
    private val app: FamilyCloudApp,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val container = app.container
        val model = when (modelClass) {
            ConnectionViewModel::class.java -> ConnectionViewModel(app, container.repository, container.settings)
            BrowserViewModel::class.java -> BrowserViewModel(app, container, extras.createSavedStateHandle())
            SyncViewModel::class.java -> SyncViewModel(app, container)
            ImageViewModel::class.java -> ImageViewModel(app, container, extras.createSavedStateHandle())
            VideoViewModel::class.java -> VideoViewModel(app, container, extras.createSavedStateHandle())
            else -> error("Unknown model ${modelClass.name}")
        }
        @Suppress("UNCHECKED_CAST")
        return model as T
    }
}
