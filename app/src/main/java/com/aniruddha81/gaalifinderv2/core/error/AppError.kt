package com.aniruddha81.gaalifinderv2.core.error

import android.database.sqlite.SQLiteException
import com.aniruddha81.gaalifinderv2.R
import io.appwrite.exceptions.AppwriteException
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Every failure the app can surface, expressed as a closed set of cases.
 *
 * Keeping errors typed (instead of passing raw [Throwable]s or strings around) means the UI layer
 * decides how something reads to a user, while the data layer only decides *what* went wrong.
 * [messageRes] is the user-facing copy; [cause] is kept for logging and is never shown.
 */
sealed class AppError(
    val messageRes: Int,
    val cause: Throwable? = null,
) {
    /** Device has no usable network connection. */
    data object NoConnection : AppError(R.string.error_no_connection)

    /** Remote catalogue is not configured for this build (missing Appwrite credentials). */
    data object RemoteNotConfigured : AppError(R.string.error_remote_not_configured)

    /** The request left the device but the server or transport failed. */
    class Network(cause: Throwable? = null) : AppError(R.string.error_network, cause)

    /** Reading or writing local storage failed — usually a full disk or a revoked URI. */
    class Storage(cause: Throwable? = null) : AppError(R.string.error_storage, cause)

    /** The device is out of space for the clip being saved. */
    data object OutOfSpace : AppError(R.string.error_out_of_space)

    /** The local database rejected a read or write. */
    class Database(cause: Throwable? = null) : AppError(R.string.error_database, cause)

    /** The clip row exists but the audio file behind it is gone. */
    data object ClipFileMissing : AppError(R.string.error_clip_file_missing)

    /** The clip could not be decoded or played. */
    class Playback(cause: Throwable? = null) : AppError(R.string.error_playback, cause)

    /** A rename was rejected because the new name is empty or only whitespace. */
    data object EmptyName : AppError(R.string.error_empty_name)

    /** A rename was rejected because another clip already uses that name. */
    data object DuplicateName : AppError(R.string.error_duplicate_name)

    /** A rename was rejected because the name contains characters a file name cannot hold. */
    data object InvalidName : AppError(R.string.error_invalid_name)

    /** No app on the device can handle the share intent. */
    data object NoShareTarget : AppError(R.string.error_no_share_target)

    /** An action needed a signed-in user and there was not one. */
    data object NotSignedIn : AppError(R.string.error_not_signed_in)

    /** The Google sign-in flow was dismissed or rejected before a session existed. */
    class SignInFailed(cause: Throwable? = null) : AppError(R.string.error_sign_in_failed, cause)

    /**
     * The picked file is over the per-file cap.
     *
     * Carries the actual size so the message can name it — a bare "too large" leaves the user
     * guessing how much they need to trim.
     */
    class FileTooLarge(val sizeBytes: Long) : AppError(R.string.error_file_too_large)

    /** The upload would push this user past their total storage allowance. */
    class QuotaExceeded(
        val usedBytes: Long,
        val limitBytes: Long,
        val isPremium: Boolean,
    ) : AppError(R.string.error_quota_exceeded)

    /** The server rejected the upload during its own re-validation of the limits. */
    data object UploadRejected : AppError(R.string.error_upload_rejected)

    /** Anything we did not anticipate. */
    class Unexpected(cause: Throwable? = null) : AppError(R.string.error_unexpected, cause)
}

/**
 * Maps a raw [Throwable] onto the closed [AppError] set.
 *
 * [CancellationException] is deliberately rethrown: swallowing it would break structured
 * concurrency by making a cancelled coroutine look like a failed one.
 */
fun Throwable.toAppError(): AppError {
    if (this is CancellationException) throw this
    return when (this) {
        is AppErrorException -> error
        is UnknownHostException -> AppError.NoConnection
        is SocketTimeoutException -> AppError.Network(this)
        is AppwriteException -> AppError.Network(this)
        is FileNotFoundException -> AppError.ClipFileMissing
        is SQLiteException -> AppError.Database(this)
        is IOException -> if (isOutOfSpace()) AppError.OutOfSpace else AppError.Storage(this)
        else -> AppError.Unexpected(this)
    }
}

private fun IOException.isOutOfSpace(): Boolean =
    message?.contains("space left", ignoreCase = true) == true ||
        message?.contains("ENOSPC", ignoreCase = true) == true

/** Lets an [AppError] travel through APIs that can only throw, without losing its type. */
class AppErrorException(val error: AppError) : Exception(error.cause)
