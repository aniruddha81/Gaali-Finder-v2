package com.aniruddha81.gaalifinderv2.ui.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aniruddha81.gaalifinderv2.R
import com.aniruddha81.gaalifinderv2.core.media.PlaybackState
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.ui.common.PlayStopGlyph
import com.aniruddha81.gaalifinderv2.ui.common.WaveformBars
import com.aniruddha81.gaalifinderv2.ui.common.formatDuration
import com.aniruddha81.gaalifinderv2.ui.theme.clipAccentFor

/**
 * One clip in the grid.
 *
 * Tap plays or stops it, long-press opens the actions sheet. The accent colour is derived from
 * the clip id, so it is stable across scrolls and restarts rather than re-rolled per composition.
 */
@Composable
fun AudioClipCard(
    clip: AudioClip,
    playback: PlaybackState,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = clipAccentFor(clip.id)
    val isPlaying = playback.isPlaying(clip.id)
    val isPreparing = playback.isPreparing(clip.id)
    val isActive = isPlaying || isPreparing
    val progress = playback.progressFor(clip.id)

    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "cardPressScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (isActive) 10.dp else 2.dp,
        animationSpec = tween(220),
        label = "cardElevation",
    )

    // Solid accent fill, like the old flat cards — bold colour rather than a faint tint.
    val containerColor = accent
    val borderColor = Color.Black.copy(alpha = if (isActive) 0.25f else 0f)
    val onAccent = Color.White

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics {
                contentDescription = clip.displayName
            },
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        tonalElevation = elevation,
        shadowElevation = elevation,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggle()
                    },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                )
                .padding(14.dp),
        ) {
            ClipHeader(clip = clip, onAccent = onAccent, isActive = isActive)

            Spacer(Modifier.height(10.dp))

            Text(
                text = clip.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = onAccent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(14.dp))

            ClipFooter(
                accent = accent,
                onAccent = onAccent,
                isPlaying = isPlaying,
                isPreparing = isPreparing,
                progress = progress,
                durationLabel = formatDuration(clip.durationMs),
            )
        }
    }
}

@Composable
private fun ClipHeader(
    clip: AudioClip,
    onAccent: Color,
    isActive: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        WaveformBars(
            isAnimating = isActive,
            color = onAccent,
            modifier = Modifier
                .height(18.dp)
                .width(26.dp),
        )

        AnimatedVisibility(
            visible = clip.isNew,
            enter = scaleIn(spring(stiffness = 700f)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            NewBadge()
        }
    }
}

@Composable
private fun NewBadge() {
    Text(
        text = stringResource(R.string.badge_new),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun ClipFooter(
    accent: Color,
    onAccent: Color,
    isPlaying: Boolean,
    isPreparing: Boolean,
    progress: Float,
    durationLabel: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PlayButton(
            accent = accent,
            onAccent = onAccent,
            isPlaying = isPlaying,
            isPreparing = isPreparing,
            progress = progress,
        )

        // Omitted entirely when the duration could not be probed, rather than showing "0:00".
        if (durationLabel != null) {
            Text(
                text = durationLabel,
                style = MaterialTheme.typography.labelMedium,
                color = onAccent.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * The play control, with a progress ring that fills as the clip plays.
 *
 * It is decorative — the whole card is the touch target — so it carries no click handler of its
 * own and is hidden from accessibility, which reads the card instead.
 */
@Composable
private fun PlayButton(
    accent: Color,
    onAccent: Color,
    isPlaying: Boolean,
    isPreparing: Boolean,
    progress: Float,
) {
    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        val animatedProgress by animateFloatAsState(
            targetValue = progress,
            animationSpec = tween(120),
            label = "playbackProgress",
        )

        if (isPreparing) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                strokeWidth = 2.dp,
                color = onAccent,
            )
        } else if (isPlaying) {
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(40.dp),
                strokeWidth = 2.dp,
                color = onAccent,
                trackColor = onAccent.copy(alpha = 0.28f),
            )
        }

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(onAccent),
            contentAlignment = Alignment.Center,
        ) {
            PlayStopGlyph(
                isPlaying = isPlaying,
                tint = accent,
                size = 16.dp,
            )
        }
    }
}
