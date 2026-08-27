package com.aniruddha81.gaalifinderv2.core.result

import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.error.toAppError
import kotlinx.coroutines.CancellationException

/**
 * The result of an operation that is allowed to fail.
 *
 * Repository and data-source methods return this instead of throwing, which makes failure part of
 * the signature: a caller cannot forget to handle it the way it can forget a `try/catch`.
 */
sealed interface DataResult<out T> {
    data class Success<out T>(val data: T) : DataResult<T>
    data class Failure(val error: AppError) : DataResult<Nothing>
}

/** Runs [block], converting any thrown exception into a typed [DataResult.Failure]. */
inline fun <T> runCatchingResult(block: () -> T): DataResult<T> = try {
    DataResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    DataResult.Failure(e.toAppError())
}

inline fun <T> DataResult<T>.onSuccess(action: (T) -> Unit): DataResult<T> = apply {
    if (this is DataResult.Success) action(data)
}

inline fun <T> DataResult<T>.onFailure(action: (AppError) -> Unit): DataResult<T> = apply {
    if (this is DataResult.Failure) action(error)
}

inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> = when (this) {
    is DataResult.Success -> DataResult.Success(transform(data))
    is DataResult.Failure -> this
}

/** Chains another fallible step, short-circuiting on the first failure. */
inline fun <T, R> DataResult<T>.flatMap(transform: (T) -> DataResult<R>): DataResult<R> =
    when (this) {
        is DataResult.Success -> transform(data)
        is DataResult.Failure -> this
    }

fun <T> DataResult<T>.getOrNull(): T? = (this as? DataResult.Success)?.data

fun <T> DataResult<T>.errorOrNull(): AppError? = (this as? DataResult.Failure)?.error
