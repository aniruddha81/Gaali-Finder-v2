package com.aniruddha81.gaalifinderv2.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.sin

/**
 * A small equaliser that animates only while a clip is playing.
 *
 * When idle it renders a flat, dimmed baseline instead of unmounting, so the card's height does
 * not jump between states.
 */
@Composable
fun WaveformBars(
    isAnimating: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 5,
) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Restart,
        ),
        label = "waveformPhase",
    )

    Canvas(modifier = modifier) {
        val gap = size.width / (barCount * 2f - 1f)
        val barWidth = gap
        val radius = CornerRadius(barWidth / 2f)

        repeat(barCount) { index ->
            // Each bar is offset along the same sine wave, which reads as a travelling pulse
            // rather than every bar bouncing in unison.
            val amplitude = if (isAnimating) {
                val offset = index * OFFSET_PER_BAR
                (sin(phase + offset) + 1f) / 2f
            } else {
                0f
            }

            val barHeight = size.height * (MIN_HEIGHT_FRACTION + amplitude * MAX_GROWTH)
            val x = index * (barWidth + gap)
            val y = (size.height - barHeight) / 2f

            drawRoundRect(
                color = color.copy(alpha = if (isAnimating) 1f else 0.35f),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

private const val OFFSET_PER_BAR = 0.7f
private const val MIN_HEIGHT_FRACTION = 0.22f
private const val MAX_GROWTH = 0.78f
