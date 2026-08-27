package com.aniruddha81.gaalifinderv2.ui.common

import android.content.Context
import com.aniruddha81.gaalifinderv2.R
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.domain.model.StorageQuota
import com.aniruddha81.gaalifinderv2.ui.home.UiMessage

/**
 * Turns a [UiMessage] into display text.
 *
 * Resolution happens here, at render time, rather than in the ViewModel — so messages stay
 * locale-correct if the user changes language while the app is running, and the ViewModel
 * stays free of `Context`.
 */
fun UiMessage.resolve(context: Context): String = when (this) {
    is UiMessage.FromError -> error.resolve(context)

    is UiMessage.FromResource ->
        if (args.isEmpty()) context.getString(resId)
        else context.getString(resId, *args.toTypedArray())

    is UiMessage.Plural -> context.resources.getQuantityString(resId, count, count)

    is UiMessage.UploadSummary -> resolveUploadSummary(context)
}

/**
 * Errors that carry data need their numbers interpolated, which a bare [AppError.messageRes]
 * lookup cannot do — a user told only "file too large" still does not know by how much.
 */
private fun AppError.resolve(context: Context): String = when (this) {
    is AppError.FileTooLarge -> context.getString(
        R.string.error_file_too_large,
        kilobytes(sizeBytes),
        kilobytes(StorageQuota.MAX_FILE_BYTES),
    )

    is AppError.QuotaExceeded -> context.getString(
        R.string.error_quota_exceeded,
        formatBytes(limitBytes),
    )

    else -> context.getString(messageRes)
}

private fun UiMessage.UploadSummary.resolveUploadSummary(context: Context): String {
    val parts = buildList {
        if (added > 0) {
            add(context.resources.getQuantityString(R.plurals.upload_added, added, added))
        }
        if (tooLargeNames.isNotEmpty()) {
            // The rejected files are named in full — a bare count would not tell the user which
            // ones to trim. One line for the whole batch, so a multi-file reject does not fire
            // several snackbars that each replace the last.
            add(
                context.getString(
                    R.string.error_files_too_large_named,
                    tooLargeNames.joinToString(", ") { "“$it”" },
                    kilobytes(StorageQuota.MAX_FILE_BYTES),
                )
            )
        }
        if (failed > 0) {
            add(context.resources.getQuantityString(R.plurals.upload_failed, failed, failed))
        }
    }

    return when (parts.size) {
        0 -> context.getString(R.string.import_cancelled)
        1 -> parts.first()
        else -> parts.joinToString(separator = " · ")
    }
}

/** Rounded up, so a 204,801-byte file never reads as exactly the 200 KB limit. */
private fun kilobytes(bytes: Long): Long = (bytes + 1023) / 1024
