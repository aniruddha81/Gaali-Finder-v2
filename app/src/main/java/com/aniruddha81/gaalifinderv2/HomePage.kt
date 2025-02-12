package com.aniruddha81.gaalifinderv2

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aniruddha81.gaalifinderv2.ui.AudioCard
import com.aniruddha81.gaalifinderv2.data.AudioFile
import com.aniruddha81.gaalifinderv2.viewmodel.AudioViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(viewModel: AudioViewModel) {

    val audioFiles by viewModel.audioFiles.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadAudioFiles()
    }

    val context = LocalContext.current

    // Debugging: Log the audioFiles state
    LaunchedEffect(audioFiles) {
        println("Audio files updated: ${audioFiles.size}")
    }


//    var audioFiles by remember { mutableStateOf<List<AudioFile>>(emptyList()) }
    var mediaPlayer = remember { mutableStateOf<MediaPlayer?>(null) }
    var playingFile by remember { mutableStateOf<AudioFile?>(null) }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
//            val oldSize = audioFiles.size

            /*val newFiles = */
            uris.mapNotNull { uri ->
                val fileName = getFileNameFromUri(context, uri)

                if (!audioFiles.any { it.fileName == fileName }) {
                    context.contentResolver.openInputStream(uri)?.let { inputStream ->
                        val byteArray = inputStream.readBytes()
                        viewModel.addLocalAudio(fileName, byteArray)
                    }
                } else {
                    Toast.makeText(
                        context,
                        "File already exists in internal storage",
                        Toast.LENGTH_SHORT
                    ).show()
                    null
                }
            }

        }

    Scaffold(
        topBar = {
            TopAppBar(

                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFF44336),
                    titleContentColor = Color(0xFFE5D7D7),
                ),
                title = {
                    Text(
                        text = "Home",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = { /* Search Action */ }) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = Color.White
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch(arrayOf("audio/mpeg")) },
                containerColor = Color(0xFFE53935),
                elevation = FloatingActionButtonDefaults.elevation(8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(125.dp),
//                contentPadding = PaddingValues(8.dp),
                content = {

                    items(audioFiles) { audioFile ->
                        AudioCard(
                            audioFile = audioFile,
                            isPlaying = playingFile == audioFile,
                            onPlayStop = {
                                if (playingFile != audioFile) {
                                    mediaPlayer.value?.release()
                                    mediaPlayer.value = MediaPlayer().apply {

                                        val uri = Uri.parse(audioFile.path)

                                        setDataSource(context, uri)
                                        prepare()
                                        start()

                                        setOnCompletionListener {
                                            playingFile = null
                                        }
                                    }
                                    playingFile = audioFile

                                } else {
                                    mediaPlayer.value?.release()
                                    mediaPlayer.value = null
                                    playingFile = null
                                }
                            },
                            onDelete = {
                                mediaPlayer.value?.release()
                                mediaPlayer.value = null
                                playingFile = null
//                                if (viewModel.deleteAudioFile( audioFile)) {
//                                    Toast.makeText(
//                                        context,
//                                        "${audioFile.fileName.dropLast(4)} Deleted",
//                                        Toast.LENGTH_SHORT
//                                    )
//                                        .show()
//                                } else {
//                                    Toast.makeText(
//                                        context,
//                                        "File Deletion Failed",
//                                        Toast.LENGTH_SHORT
//                                    )
//                                        .show()
//                                }
                                try {
                                    viewModel.deleteAudioFile(audioFile)
                                    Toast.makeText(
                                        context,
                                        "${audioFile.fileName.dropLast(4)} Deleted",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "File Deletion Failed: ${e.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onShare = {
                                shareAudioFile(context, audioFile.path)
                            }
                        )
                    }
                    item {
                        Spacer(Modifier.height(100.dp))
                    }
                }
            )
        }
    }
}


fun getFileNameFromUri(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) return cursor.getString(nameIndex)
        }
    }
    return "unknown_audio.mp3"
}

fun shareAudioFile(context: Context, filePath: String) {

    val file = File(filePath)

    if (!file.exists()) return

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/mpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Audio File"))
}