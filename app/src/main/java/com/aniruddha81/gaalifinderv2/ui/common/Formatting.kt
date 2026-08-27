package com.aniruddha81.gaalifinderv2.ui.common

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formats a clip length for display.
 *
 * Clips are short, so anything under a minute reads as `0:04` and longer ones as `1:23`.
 * A duration we never managed to probe returns null so the caller can omit the chip entirely
 * rather than showing a misleading `0:00`.
 */
fun formatDuration(durationMs: Long): String? {
    if (durationMs <= 0) return null

    val totalSeconds = (durationMs / 1000.0).roundToInt().coerceAtLeast(1)
    return formatClock(totalSeconds)
}

/**
 * Formats a live playback position as `m:ss`, allowing `0:00`.
 *
 * Unlike [formatDuration] this never returns null and never floors to 1 second: it is the
 * running counter on a playing card, so it has to be able to land on `0:00` at the end.
 */
fun formatElapsed(positionMs: Int): String =
    formatClock((positionMs.coerceAtLeast(0) / 1000.0).roundToInt())

private fun formatClock(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", safe / 60, safe % 60)
}

/**
 * Formats a byte count for the quota UI.
 *
 * Uses KB below a megabyte and one decimal place above it, so "9.8 MB of 10 MB" reads as the
 * near-miss it is rather than rounding up to a confusing "10 MB of 10 MB".
 */
fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    return when {
        safe < 1024 -> "$safe B"
        safe < 1024 * 1024 -> "${(safe + 1023) / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
    }
}
