package com.eddies.app.core.result

/** What went wrong, in terms the UI can act on rather than a raw exception. */
enum class ErrorType {
    NETWORK,
    RATE_LIMITED,
    NOT_FOUND,
    AUTH,
    PARSE,
    STORAGE,
    UNKNOWN,
}

data class AppError(
    val type: ErrorType,
    val message: String,
    val cause: Throwable? = null,
)

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>

    companion object {
        fun <T> success(value: T): AppResult<T> = Success(value)
        fun failure(type: ErrorType, message: String, cause: Throwable? = null): AppResult<Nothing> =
            Failure(AppError(type, message, cause))
    }
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value
