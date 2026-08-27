package com.aniruddha81.gaalifinderv2.ui.common

import android.content.Context
import com.aniruddha81.gaalifinderv2.R
import com.aniruddha81.gaalifinderv2.ui.home.UiMessage

/**
 * Turns a [UiMessage] into display text.
 *
 * Resolution happens here, at render time, rather than in the ViewModel — so messages stay
 * locale-correct if the user changes language while the app is running, and the ViewModel
 * stays free of `Context`.
 */
fun UiMessage.resolve(context: Context): String = when (this) {
    is UiMessage.FromError -> context.getString(error.messageRes)

    is UiMessage.FromResource ->
        if (args.isEmpty()) context.getString(resId)
        else context.getString(resId, *args.toTypedArray())

    is UiMessage.Plural -> context.resources.getQuantityString(resId, count, count)

    is UiMessage.ImportSummary -> resolveImportSummary(context)
}

private fun UiMessage.ImportSummary.resolveImportSummary(context: Context): String {
    val parts = buildList {
        if (added > 0) add(context.resources.getQuantityString(R.plurals.import_added, added, added))
        if (skipped > 0) {
            add(context.resources.getQuantityString(R.plurals.import_skipped, skipped, skipped))
        }
        if (failed > 0) {
            add(context.resources.getQuantityString(R.plurals.import_failed, failed, failed))
        }
    }

    return when (parts.size) {
        0 -> context.getString(R.string.import_cancelled)
        1 -> parts.first()
        else -> parts.joinToString(separator = " · ")
    }
}
