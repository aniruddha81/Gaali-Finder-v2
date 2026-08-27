package com.aniruddha81.gaalifinderv2.ui.home.components

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aniruddha81.gaalifinderv2.R
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType

/**
 * The shortest gap, in millis, between two reaction dispatches from one card.
 *
 * A single toggle is one server write; mashing like/dislike would otherwise fire one request
 * per tap and, since they race each other, surface as "couldn't reach the server". Taps inside
 * this window are swallowed here in the UI so they never reach the ViewModel.
 */
private const val REACTION_DEBOUNCE_MS = 600L

/**
 * The like/dislike pair on a card.
 *
 * Both are always tappable, including for guests — the tap is what triggers the sign-in prompt,
 * so a disabled button would leave a guest with no way to discover that reacting is possible.
 */
@Composable
fun ReactionButtons(
    myReaction: ReactionType,
    likeCount: Int,
    dislikeCount: Int,
    onAccent: Color,
    onReact: (ReactionType) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One clock for the whole pair, so alternating like -> dislike -> like is throttled too,
    // not just repeated taps on the same chip. Survives recomposition; resets with the card.
    val lastReactAt = remember { longArrayOf(0L) }
    val debouncedReact: (ReactionType) -> Unit = { reaction ->
        val now = SystemClock.elapsedRealtime()
        if (now - lastReactAt[0] >= REACTION_DEBOUNCE_MS) {
            lastReactAt[0] = now
            onReact(reaction)
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ReactionChip(
            isActive = myReaction == ReactionType.Like,
            count = likeCount,
            onAccent = onAccent,
            contentDescription = stringResource(
                if (myReaction == ReactionType.Like) R.string.cd_undo_like else R.string.cd_like
            ),
            isDislike = false,
            onClick = { debouncedReact(ReactionType.Like) },
        )

        ReactionChip(
            isActive = myReaction == ReactionType.Dislike,
            count = dislikeCount,
            onAccent = onAccent,
            contentDescription = stringResource(
                if (myReaction == ReactionType.Dislike) R.string.cd_undo_dislike
                else R.string.cd_dislike
            ),
            isDislike = true,
            onClick = { debouncedReact(ReactionType.Dislike) },
        )
    }
}

/**
 * One reaction control.
 *
 * The active state is carried by a filled pill rather than only by colour, so it stays legible
 * against the card's own accent fill and for users who cannot rely on hue alone.
 */
@Composable
private fun ReactionChip(
    isActive: Boolean,
    count: Int,
    onAccent: Color,
    contentDescription: String,
    isDislike: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 800f),
        label = "reactionScale",
    )

    val background = if (isActive) onAccent.copy(alpha = 0.25f) else Color.Transparent

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.ThumbUp,
            contentDescription = null,
            tint = onAccent.copy(alpha = if (isActive) 1f else 0.75f),
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                // The icon set here ships only a thumbs-up, so the dislike is the same glyph
                // turned over rather than an extra dependency on icons-extended.
                .rotate(if (isDislike) 180f else 0f),
        )

        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = onAccent.copy(alpha = if (isActive) 1f else 0.8f),
            )
        }
    }
}
