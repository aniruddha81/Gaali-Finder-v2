package com.aniruddha81.gaalifinderv2

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aniruddha81.gaalifinderv2.model.AudioFile

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AudioCard(
    audioFile: AudioFile,
    isPlaying: Boolean,
    onPlayStop: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    val cardColor = remember { randomMaterial300Color() }

    if (showDialog) {
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
            .fillMaxWidth()
            .padding(8.dp)
            .combinedClickable(onClick = {}, onLongClick = { showDialog = true }),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // File Name
            Text(
                text = audioFile.fileName.dropLast(4),
                color = Color.Black,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .fillMaxWidth()
                    .basicMarquee()
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play/Stop Button
                IconButton(
                    onClick = onPlayStop,
                    modifier = Modifier
                        .size(36.dp)
//                        .background(
//                            color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
//                            shape = CircleShape
//                        )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Clear else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Stop" else "Play",
//                        tint = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Share Button
                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(36.dp)
//                        .background(
//                            color = MaterialTheme.colorScheme.tertiaryContainer,
//                            shape = CircleShape
//                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
//                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                }

                /*// Delete Button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(36.dp)
//                        .background(
//                            color = MaterialTheme.colorScheme.errorContainer,
//                            shape = CircleShape
//                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
//                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                }*/
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
