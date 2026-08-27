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
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
