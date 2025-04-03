package com.aniruddha81.gaalifinderv2

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.aniruddha81.gaalifinderv2.models.AudioFile
import com.aniruddha81.gaalifinderv2.ui.AudioCard
import com.aniruddha81.gaalifinderv2.ui.MainAppBar
import com.aniruddha81.gaalifinderv2.ui.SearchWidgetState
import com.aniruddha81.gaalifinderv2.viewmodel.AudioViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(viewModel: AudioViewModel = hiltViewModel()) {

    val searchWidgetState by viewModel.searchWidgetState

    val audioFiles by viewModel.audioFiles.collectAsState()

//     search query vars
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filteredAudioFiles by viewModel.filteredAudioFiles.collectAsState()


    LaunchedEffect(Unit) {
        viewModel.loadAudioFilesFromRoomDB()
    }

    val context = LocalContext.current

    var mediaPlayer by rememberSaveable { mutableStateOf<MediaPlayer?>(null) }

    // this will keep track of the composition state of the audio file
    var playingFileId by rememberSaveable { mutableStateOf<Long?>(null) }

    fun getFileNameFromUri(uri: Uri): String {

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) return cursor.getString(nameIndex)
            }
        }
        return "unknown_audio.mp3"
    }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) {
                Toast.makeText(context, "No files selected", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }

            var existCount = 0
            val addedFiles = mutableListOf<String>()


            // adding files to ips
            uris.forEach { uri ->
                val fileName = getFileNameFromUri(uri)

                if (audioFiles.any { it.fileName == fileName }) {
                    existCount++
                } else {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val outputStream = ByteArrayOutputStream()
                            input.copyTo(outputStream)
                            viewModel.addLocalAudio(fileName, outputStream.toByteArray())
                            addedFiles.add(fileName.dropLast(4))
                        }
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Failed to read file: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            // Handle different cases with correct messages
            when {
                addedFiles.isEmpty() && existCount == uris.size -> { // All selected files exist
                    if (uris.size == 1) {
                        Toast.makeText(context, "Selected file already exists", Toast.LENGTH_SHORT)
                            .show()
                    } else {
                        Toast.makeText(
                            context,
                            "All selected files already exist",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                addedFiles.isEmpty() -> { // No new files added (some failed or all exist)
                    Toast.makeText(context, "No new files were added", Toast.LENGTH_SHORT).show()
                }

                existCount == 0 -> { // All files are new
                    val message = if (addedFiles.size == 1) {
                        "${addedFiles[0]} added"
                    } else {
                        "${addedFiles.size} files added"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }

                addedFiles.size == 1 && existCount == 1 -> { // One file added, one already existed
                    Toast.makeText(
                        context,
                        "${addedFiles[0]} added, 1 file already exists",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                addedFiles.size == 1 -> { // Only one file added
                    Toast.makeText(context, "${addedFiles[0]} added", Toast.LENGTH_SHORT).show()
                }

                existCount > 0 -> { // Some files added, some existed
                    Toast.makeText(
                        context,
                        "${addedFiles.size} clips added, $existCount already exist",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    fun onDelete(audioFile: AudioFile) {
        mediaPlayer?.release()
        mediaPlayer = null
        playingFileId = null

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
    }

    fun onPlayStop(audioFile: AudioFile) {
        if (playingFileId != audioFile.id) {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {

                val uri = audioFile.path.toUri()
                setDataSource(context, uri)
                prepare()
                start()

                setOnCompletionListener {
                    playingFileId = null
                    mediaPlayer?.release()
                    mediaPlayer = null
                }
            }
            playingFileId = audioFile.id
            if (audioFile.isNew) {
                viewModel.markAsSeen(audioFile) // Mark as seen without affecting playback,otherwise it was creating a recomposition when playing ,that's why it was not working on the play button onclick
            }

        } else {
            mediaPlayer?.release()
            mediaPlayer = null
            playingFileId = null
        }
    }

    fun shareAudioFile(filePath: String) {

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

    Scaffold(
        topBar = {
            MainAppBar(
                searchWidgetState = searchWidgetState,
                searchTextState = searchQuery,
                onTextChange = {
                    viewModel.updateSearchQuery(it)
                },
                onCloseClicked = {
                    viewModel.updateSearchQuery("")
                    viewModel.updateSearchWidgetState(newValue = SearchWidgetState.CLOSED)
                },
                onSearchTriggered = {
                    viewModel.updateSearchWidgetState(newValue = SearchWidgetState.OPENED)
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
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 2.dp, end = 2.dp),

                ) {
                val state = rememberPullToRefreshState()
                var isRefreshing by remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        coroutineScope.launch {
                            isRefreshing = true
                            viewModel.refreshAudioFiles()
                            delay(1.seconds)
                            isRefreshing = false
                        }
                    },
                    state = state,
                    indicator = {
                        Indicator(
                            modifier = Modifier.align(Alignment.TopCenter),
                            isRefreshing = isRefreshing,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            state = state
                        )
                    }
                ) {
                    if (filteredAudioFiles.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No audio files found",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                    } else {
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(3),
                            content = {
                                items(items = filteredAudioFiles, key = { it.id }) { audio ->
                                    AudioCard(
                                        audioFile = audio,
                                        isNew = audio.isNew,
                                        isPlaying = playingFileId == audio.id,
                                        onPlayStop = {
                                            onPlayStop(audio)
                                        },
                                        onDelete = { onDelete(audio) },
                                        onShare = {
                                            shareAudioFile(audio.path)
                                        },
                                        onRename = {
                                            viewModel.renameAudioFile(audio.id, it)
                                        },
                                        onMarkAsSeen = {
                                            viewModel.markAsSeen(audio)
                                        }
                                    )
                                }
                                item {
                                    Spacer(Modifier.height(500.dp))
                                }
                            }
                        )
                        DisposableEffect(Unit) {
                            onDispose {
                                mediaPlayer?.release()
                            }
                        }
                    }
                }
            }
            Text(
                text = stringResource(id = R.string.developer),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )
            )
        }
    }
}