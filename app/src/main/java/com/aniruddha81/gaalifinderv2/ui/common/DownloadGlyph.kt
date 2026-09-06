package com.aniruddha81.gaalifinderv2.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A downward arrow above a short tray, bobbing gently — the "fetching this clip" state.
 *
 * Drawn rather than imported, to match [PlayStopGlyph]: two shapes are not worth the extended
 * icon library. It pairs with the indeterminate ring the card already shows while preparing, so
 * the first-play download reads as a download rather than a generic wait.
 */
@Composable
fun DownloadGlyph(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val transition = rememberInfiniteTransition(label = "download")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "downloadBob",
    )

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = h * 0.12f, cap = StrokeCap.Round)

        // The arrow travels over a small vertical range; the tray stays put.
        val travel = h * 0.12f
        val shaftTop = h * 0.08f + bob * travel
        val shaftBottom = h * 0.60f + bob * travel
        val centerX = w / 2f

        // Shaft.
        drawLine(
            color = tint,
            start = Offset(centerX, shaftTop),
            end = Offset(centerX, shaftBottom),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )

        // Arrow head.
        val headSpread = w * 0.22f
        drawPath(
            path = Path().apply {
                moveTo(centerX - headSpread, shaftBottom - headSpread)
                lineTo(centerX, shaftBottom)
                lineTo(centerX + headSpread, shaftBottom - headSpread)
            },
            color = tint,
            style = stroke,
        )

        // Tray the file lands in.
        val trayY = h * 0.86f
        val trayInset = w * 0.18f
        drawLine(
            color = tint,
            start = Offset(trayInset, trayY),
            end = Offset(w - trayInset, trayY),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}
