package app.familycloud.data.webdav

sealed interface WebDavError {
    data object Unauthorized : WebDavError
    data object Forbidden : WebDavError
    data object NotFound : WebDavError
    data object MethodNotAllowed : WebDavError
    data object Conflict : WebDavError
    data object Locked : WebDavError
    data object InsufficientStorage : WebDavError
    data object Timeout : WebDavError
    data object Ssl : WebDavError
    data object InvalidUrl : WebDavError
    data object Network : WebDavError
    data class Http(val code: Int) : WebDavError
}

sealed interface WebDavResult<out T> {
    data class Ok<T>(val value: T) : WebDavResult<T>
    data class Err(val error: WebDavError) : WebDavResult<Nothing>
}

fun <T> WebDavResult<T>.getOrNull(): T? = (this as? WebDavResult.Ok)?.value
