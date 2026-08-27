package com.aniruddha81.gaalifinderv2.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * A play triangle that morphs into a stop square.
 *
 * Drawn rather than imported: `material-icons-extended` has no stop glyph in the core set, and
 * pulling in the whole extended library for two shapes would cost far more than this.
 */
@Composable
fun PlayStopGlyph(
    isPlaying: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val progress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "playStopMorph",
    )

    Canvas(modifier = modifier.size(size)) {
        if (progress < 0.5f) {
            drawPlayTriangle(tint, morph = progress * 2f)
        } else {
            drawStopSquare(tint, morph = (progress - 0.5f) * 2f)
        }
    }
}

/** Triangle shrinking towards the centre as [morph] runs 0 -> 1. */
private fun DrawScope.drawPlayTriangle(tint: Color, morph: Float) {
    val scale = lerp(1f, 0.6f, morph)
    val width = size.width * scale
    val height = size.height * scale
    val left = (size.width - width) / 2f
    val top = (size.height - height) / 2f

    val path = Path().apply {
        // Slightly inset on the left so the triangle looks optically centred in a circle.
        moveTo(left + width * 0.12f, top)
        lineTo(left + width * 0.12f, top + height)
        lineTo(left + width, top + height / 2f)
        close()
    }
    drawPath(path = path, color = tint.copy(alpha = 1f - morph * 0.4f))
}

/** Square growing out from the centre as [morph] runs 0 -> 1. */
private fun DrawScope.drawStopSquare(tint: Color, morph: Float) {
    val side = lerp(size.minDimension * 0.5f, size.minDimension * 0.72f, morph)
    val offset = Offset((size.width - side) / 2f, (size.height - side) / 2f)

    drawRoundRect(
        color = tint.copy(alpha = 0.6f + morph * 0.4f),
        topLeft = offset,
        size = Size(side, side),
        cornerRadius = CornerRadius(side * 0.22f),
    )
}
