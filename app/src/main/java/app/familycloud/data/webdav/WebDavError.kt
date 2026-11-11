package app.familycloud.data.webdav

sealed interface WebDavError {
    data class Unauthorized(val scheme: String?, val schemes: List<String> = listOfNotNull(scheme)) : WebDavError
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
    data object HttpDisabled : WebDavError
    data class Http(val code: Int) : WebDavError
}

fun WebDavError.code(): String = when (this) {
    WebDavError.Network -> "NETWORK"
    WebDavError.Timeout -> "TIMEOUT"
    WebDavError.Ssl -> "SSL"
    WebDavError.HttpDisabled -> "HTTP_DISABLED"
    is WebDavError.Unauthorized -> when (kind()) {
        UnauthorizedKind.Basic -> "UNAUTHORIZED_BASIC"
        UnauthorizedKind.Digest -> "UNAUTHORIZED_DIGEST"
        UnauthorizedKind.Unsupported -> "UNAUTHORIZED_SCHEME"
        UnauthorizedKind.Generic -> "UNAUTHORIZED"
    }
    WebDavError.Forbidden -> "FORBIDDEN"
    WebDavError.NotFound -> "NOT_FOUND"
    WebDavError.MethodNotAllowed -> "METHOD"
    WebDavError.Conflict -> "CONFLICT"
    WebDavError.Locked -> "LOCKED"
    WebDavError.InsufficientStorage -> "STORAGE"
    WebDavError.InvalidUrl -> "BAD_URL"
    is WebDavError.Http -> "HTTP_$code"
}

enum class UnauthorizedKind { Basic, Digest, Unsupported, Generic }

/** Что предлагал сервер при отказе: только Basic, только Digest, оба/неизвестно или то, что приложение не умеет. */
fun WebDavError.Unauthorized.kind(): UnauthorizedKind {
    val supported = schemes.mapNotNull { AuthScheme.fromChallenge(it) }.toSet()
    return when {
        schemes.isNotEmpty() && supported.isEmpty() -> UnauthorizedKind.Unsupported
        supported == setOf(AuthScheme.Basic) -> UnauthorizedKind.Basic
        supported == setOf(AuthScheme.Digest) -> UnauthorizedKind.Digest
        else -> UnauthorizedKind.Generic
    }
}

fun WebDavError.retries(): Boolean = when (this) {
    WebDavError.Timeout, WebDavError.Network, WebDavError.Ssl -> true
    is WebDavError.Http -> code in 500..599
    else -> false
}

class HttpDisabledException : java.io.IOException()

sealed interface WebDavResult<out T> {
    data class Ok<T>(val value: T) : WebDavResult<T>
    data class Err(val error: WebDavError) : WebDavResult<Nothing>
}

fun <T> WebDavResult<T>.getOrNull(): T? = (this as? WebDavResult.Ok)?.value
