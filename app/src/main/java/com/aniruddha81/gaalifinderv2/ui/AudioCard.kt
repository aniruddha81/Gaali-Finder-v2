package com.aniruddha81.gaalifinderv2.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniruddha81.gaalifinderv2.data.AudioFile

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioCard(
    audioFile: AudioFile,
    isPlaying: Boolean,
    onPlayStop: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var renameAudio by remember { mutableStateOf(audioFile.fileName) }
    var renameTextField by remember { mutableStateOf(false) }
    val cardColor = remember { randomMaterial300Color() }

    if (showDialog && audioFile.source == "local") {
        AlertDialog(
            title = { Text(text = "Delete Audio?") },
            text = { Text(text = "Are you sure to delete this audio?") },
            onDismissRequest = { showDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDialog = false
                    }, colors = ButtonDefaults.buttonColors(
                        containerColor = cardColor
                    )
                ) {
                    Text(text = "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(text = "Cancel", color = cardColor)
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp)
            .combinedClickable(
                onClick = {},
                onDoubleClick = {},
                onLongClick = { showDialog = true })
            .wrapContentHeight(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // File Name
            Text(
                text = audioFile.fileName.dropLast(4),
                color = Color.Black,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(bottom = 6.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play/Stop Button
                Box(
                    modifier = Modifier
                        .size(35.dp) // Reduced background size
                        .background(
                            color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onPlayStop) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Clear else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Stop" else "Play",
                            tint = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(25.dp) // Keep icon size unchanged
                        )
                    }
                }

                //      share button
                Box(
                    modifier = Modifier
                        .size(35.dp) // Reduced background size
                        .background(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp) // Keep icon size unchanged
                        )
                    }
                }
            }
        }
    }
}


fun randomMaterial300Color(): Color {
    val materialColors300 = listOf(
        Color(0xFFEF9A9A),
        Color(0xFFF48FB1),
        Color(0xFF42A5F5),
        Color(0xFF66BB6A),
        Color(0xFFFFB300),
        Color(0xFFFF7043),
    )
    return materialColors300.random()
}
