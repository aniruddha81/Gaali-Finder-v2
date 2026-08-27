package com.aniruddha81.gaalifinderv2.ui.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aniruddha81.gaalifinderv2.R
import com.aniruddha81.gaalifinderv2.ui.common.MicIcon
import com.aniruddha81.gaalifinderv2.ui.common.UploadIcon

/**
 * The plus button, expanded into a small speed-dial the way Google Keep's add button works.
 *
 * Tapping the main button toggles [expanded]; while expanded a dimming scrim fills the screen
 * (tapping it collapses the menu) and two labelled mini-buttons rise out of the FAB with a
 * short staggered slide. The plus glyph rotates into a cross so the same button also closes it.
 *
 * State is hoisted so the host can force it shut — e.g. the moment an upload begins.
 */
@Composable
fun AddClipFab(
    expanded: Boolean,
    isUploading: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onUploadFiles: () -> Unit,
    onRecordVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = tween(220),
        label = "fabRotation",
    )

    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Voice sits closest to the FAB, files above it — closest option is the newer one.
            MiniAction(
                visible = expanded && !isUploading,
                delayMillis = 60,
                label = stringResource(R.string.add_record_voice),
                icon = MicIcon,
                onClick = {
                    onExpandedChange(false)
                    onRecordVoice()
                },
            )
            MiniAction(
                visible = expanded && !isUploading,
                delayMillis = 0,
                label = stringResource(R.string.add_upload_files),
                icon = UploadIcon,
                onClick = {
                    onExpandedChange(false)
                    onUploadFiles()
                },
            )

            FloatingActionButton(
                onClick = {
                    if (!isUploading) onExpandedChange(!expanded)
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    // The plus rotates a full 45° into a cross and back; the glyph swap happens
                    // at the midpoint so neither shape is ever seen at an odd angle.
                    Icon(
                        imageVector = if (rotation > 22.5f) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = stringResource(
                            if (expanded) R.string.cd_close_add_menu else R.string.cd_add_menu
                        ),
                        modifier = Modifier.rotate(rotation),
                    )
                }
            }
        }
    }
}

/**
 * Full-screen scrim shown behind the expanded menu.
 *
 * Kept separate from [AddClipFab] so the host can place it below the FAB in the Scaffold's
 * z-order — it must cover the grid but not the button itself.
 */
@Composable
fun AddClipScrim(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(180)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
}

@Composable
private fun MiniAction(
    visible: Boolean,
    delayMillis: Int,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180, delayMillis = delayMillis)) +
            slideInVertically(tween(220, delayMillis = delayMillis)) { it / 2 } +
            scaleIn(tween(180, delayMillis = delayMillis), initialScale = 0.8f),
        exit = fadeOut(tween(120)) +
            slideOutVertically(tween(160)) { it / 2 } +
            scaleOut(tween(120), targetScale = 0.8f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The label and the mini-FAB are one tap target, so tapping the text fires the
            // same action as tapping the icon.
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            SmallFloatingActionButton(
                onClick = onClick,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(imageVector = icon, contentDescription = null)
            }
        }
    }
}
