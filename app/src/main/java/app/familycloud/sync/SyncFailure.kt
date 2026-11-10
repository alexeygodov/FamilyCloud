package app.familycloud.sync

import app.familycloud.data.webdav.WebDavError
import app.familycloud.data.webdav.code
import app.familycloud.data.webdav.retries

sealed interface SyncFailure {
    val retryable: Boolean
    val code: String

    data class Web(val error: WebDavError) : SyncFailure {
        override val retryable: Boolean get() = error.retries()
        override val code: String get() = error.code()
    }

    data object NoConnection : SyncFailure {
        override val retryable: Boolean = false
        override val code: String = "NO_CONNECTION"
    }

    data object MediaPermission : SyncFailure {
        override val retryable: Boolean = false
        override val code: String = "MEDIA_PERMISSION"
    }

    data object BadFolder : SyncFailure {
        override val retryable: Boolean = false
        override val code: String = "BAD_FOLDER"
    }

    data object LocalFile : SyncFailure {
        override val retryable: Boolean = false
        override val code: String = "LOCAL_FILE"
    }
}
