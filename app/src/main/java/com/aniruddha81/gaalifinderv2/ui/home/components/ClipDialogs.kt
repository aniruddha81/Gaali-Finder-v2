package com.aniruddha81.gaalifinderv2.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aniruddha81.gaalifinderv2.R
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.AuthUser
import com.aniruddha81.gaalifinderv2.domain.repository.StorageUsage
import com.aniruddha81.gaalifinderv2.ui.common.formatBytes

/** Confirmation before a clip is removed from the shared catalogue for everyone. */
@Composable
fun DeleteClipDialog(
    clip: AudioClip,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title, clip.displayName)) },
        text = { Text(stringResource(R.string.delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Long-press menu for a clip.
 *
 * There is no rename: the file name belongs to the shared catalogue now, so letting one user
 * rewrite it would change what every other user sees. Delete is only offered to the uploader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipActionsSheet(
    clip: AudioClip,
    canDelete: Boolean,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = clip.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            Text(
                text = stringResource(R.string.uploaded_by, clip.uploaderName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(Modifier.height(8.dp))

            SheetAction(
                icon = { Icon(Icons.Default.Share, contentDescription = null) },
                label = stringResource(R.string.action_share),
                onClick = onShare,
            )

            if (canDelete) {
                SheetAction(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    label = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
    }
}

/**
 * Shown when an upload was blocked by the total-storage cap.
 *
 * The "Upgrade" button routes to a placeholder screen rather than a checkout — there is no
 * payment integration yet, and the route exists so one can be dropped in without touching the
 * quota logic that led here.
 */
@Composable
fun QuotaExceededDialog(
    error: AppError.QuotaExceeded,
    onUpgrade: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quota_title)) },
        text = {
            Text(
                stringResource(
                    if (error.isPremium) R.string.quota_body_premium else R.string.quota_body_free,
                    formatBytes(error.usedBytes),
                    formatBytes(error.limitBytes),
                )
            )
        },
        confirmButton = {
            // A premium user who is already at their ceiling has nothing to upgrade to, so the
            // button would be a dead end for them.
            if (error.isPremium) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            } else {
                TextButton(onClick = onUpgrade) { Text(stringResource(R.string.action_upgrade)) }
            }
        },
        dismissButton = {
            if (!error.isPremium) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) }
            }
        },
    )
}

/** The placeholder that a real payment flow will eventually replace. */
@Composable
fun UpgradePlaceholderDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.upgrade_title)) },
        text = { Text(stringResource(R.string.upgrade_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

/** Account details and sign-out, plus how much of the allowance is left. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    user: AuthUser,
    usage: StorageUsage?,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = user.email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (usage != null) {
                Spacer(Modifier.height(20.dp))

                Text(
                    text = stringResource(
                        R.string.storage_used,
                        formatBytes(usage.usedBytes),
                        formatBytes(usage.limitBytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { usage.fractionUsed },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = stringResource(
                        if (usage.isPremium) R.string.plan_premium else R.string.plan_free
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))

            SheetAction(
                icon = {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                },
                label = stringResource(R.string.action_sign_out),
                onClick = onSignOut,
                horizontalPadding = 0.dp,
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    horizontalPadding: androidx.compose.ui.unit.Dp = 24.dp,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Spacer(Modifier.width(20.dp))
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tint)
        }
    }
}
