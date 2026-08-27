package com.aniruddha81.gaalifinderv2.ui.home.components

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aniruddha81.gaalifinderv2.R
import com.aniruddha81.gaalifinderv2.core.media.VoiceRecorder
import com.aniruddha81.gaalifinderv2.ui.common.MicIcon
import com.aniruddha81.gaalifinderv2.ui.common.PlayStopGlyph
import com.aniruddha81.gaalifinderv2.ui.common.WaveformBars
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.Locale

/**
 * Records a single voice clip, then asks the user to confirm before it is uploaded.
 *
 * The sheet owns a [VoiceRecorder] and a throwaway [MediaPlayer] for previewing the result.
 * Neither touches the app-wide `AudioPlayer`, so catalogue playback and this preview can never
 * fight over the same state. Everything is released in [DisposableEffect] when the sheet leaves
 * the composition — including deleting the take if the user backed out without uploading.
 *
 * Flow: idle → recording → (pause/resume)* → review → Upload | Discard | Record again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceRecorderSheet(
    onUpload: (file: File, displayName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recorder = remember { VoiceRecorder(context) }
    val preview = remember { PreviewPlayer() }

    var uiState by remember { mutableStateOf(RecorderUi.Idle) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var recordedFile by remember { mutableStateOf<File?>(null) }
    var startError by remember { mutableStateOf(false) }
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // The editable clip name, seeded with a timestamp default when the take finishes. Illegal
    // characters are cleaned up by the repository on upload, so the field only guards against
    // an entirely blank name.
    var nameInput by remember { mutableStateOf("") }

    // Set once the take has been committed to upload, so teardown must NOT delete the file.
    var handedOff by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            preview.release()
            recorder.release()
            if (!handedOff) recordedFile?.let { runCatching { it.delete() } }
        }
    }

    // True when a take was cut short because it hit the file-size ceiling, so the sheet can say
    // so instead of the user wondering why recording stopped on its own.
    var stoppedAtSizeLimit by remember { mutableStateOf(false) }

    fun startRecording() {
        startError = false
        stoppedAtSizeLimit = false
        if (runCatching { recorder.start() }.isSuccess) {
            uiState = RecorderUi.Recording
        } else {
            startError = true
        }
    }

    fun finishRecording(hitSizeLimit: Boolean = false) {
        val file = recorder.stop()
        elapsedMs = recorder.elapsedMs
        if (file == null || elapsedMs < VoiceRecorder.MIN_DURATION_MS) {
            file?.let { runCatching { it.delete() } }
            recordedFile = null
            uiState = RecorderUi.Idle
            startError = false
            return
        }
        stoppedAtSizeLimit = hitSizeLimit
        recordedFile = file
        nameInput = defaultRecordingName(context)
        uiState = RecorderUi.Review
    }

    fun resetForRerecord() {
        preview.stop()
        isPreviewPlaying = false
        recordedFile?.let { runCatching { it.delete() } }
        recordedFile = null
        elapsedMs = 0L
        stoppedAtSizeLimit = false
        nameInput = ""
        uiState = RecorderUi.Idle
    }

    // Drives the on-screen timer while recording, and cuts the take off the instant the output
    // file crosses VoiceRecorder.MAX_SIZE_BYTES (~190 KB) so it never grows past the upload cap.
    LaunchedEffect(uiState) {
        if (uiState == RecorderUi.Recording) {
            while (isActive) {
                elapsedMs = recorder.elapsedMs
                if (recorder.isLimitReached ||
                    recorder.currentSizeBytes >= VoiceRecorder.MAX_SIZE_BYTES
                ) {
                    finishRecording(hitSizeLimit = true)
                    break
                }
                delay(100)
            }
        }
    }

    LaunchedEffect(Unit) {
        preview.onCompletion = { isPreviewPlaying = false }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (uiState == RecorderUi.Recording || uiState == RecorderUi.Paused) {
                confirmDiscard = true
            } else {
                onDismiss()
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = context.getString(R.string.recorder_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = context.getString(
                    when {
                        startError -> R.string.recorder_start_failed
                        uiState == RecorderUi.Recording -> R.string.recorder_hint_recording
                        uiState == RecorderUi.Paused -> R.string.recorder_hint_paused
                        uiState == RecorderUi.Review && stoppedAtSizeLimit ->
                            R.string.recorder_hint_size_limit
                        uiState == RecorderUi.Review -> R.string.recorder_hint_review
                        else -> R.string.recorder_hint_idle
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (startError || (uiState == RecorderUi.Review && stoppedAtSizeLimit))
                    MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = formatDuration(elapsedMs),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(Modifier.height(16.dp))

            WaveformBars(
                isAnimating = uiState == RecorderUi.Recording,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(40.dp),
            )

            Spacer(Modifier.height(28.dp))

            when (uiState) {
                RecorderUi.Idle -> RoundIconButton(
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError,
                    contentDescription = context.getString(R.string.cd_recorder_start),
                    onClick = ::startRecording,
                ) {
                    Icon(
                        imageVector = MicIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(30.dp),
                    )
                }

                RecorderUi.Recording, RecorderUi.Paused -> Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (uiState == RecorderUi.Recording) {
                                recorder.pause()
                                uiState = RecorderUi.Paused
                            } else {
                                recorder.resume()
                                uiState = RecorderUi.Recording
                            }
                        },
                    ) {
                        Text(
                            context.getString(
                                if (uiState == RecorderUi.Recording) R.string.cd_recorder_pause
                                else R.string.cd_recorder_resume
                            )
                        )
                    }

                    Button(onClick = ::finishRecording) {
                        Text(context.getString(R.string.cd_recorder_stop))
                    }
                }

                RecorderUi.Review -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RoundIconButton(
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                            contentDescription = context.getString(
                                if (isPreviewPlaying) R.string.cd_recorder_pause_preview
                                else R.string.cd_recorder_play_preview
                            ),
                            onClick = {
                                val file = recordedFile ?: return@RoundIconButton
                                if (isPreviewPlaying) {
                                    preview.pause()
                                    isPreviewPlaying = false
                                } else {
                                    preview.playOrResume(file)
                                    isPreviewPlaying = true
                                }
                            },
                        ) {
                            PlayStopGlyph(
                                isPlaying = isPreviewPlaying,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }

                        TextButton(onClick = ::resetForRerecord) {
                            Text(context.getString(R.string.recorder_rerecord))
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    val nameBlank = nameInput.isBlank()
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it.take(120) },
                        singleLine = true,
                        isError = nameBlank,
                        label = { Text(context.getString(R.string.recorder_name_label)) },
                        supportingText = if (nameBlank) {
                            { Text(context.getString(R.string.error_empty_name)) }
                        } else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { confirmDiscard = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(context.getString(R.string.recorder_discard))
                        }

                        Button(
                            onClick = {
                                val ready = recordedFile ?: return@Button
                                preview.stop()
                                handedOff = true
                                onUpload(ready, nameInput.trim())
                            },
                            enabled = !nameBlank,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(context.getString(R.string.recorder_upload))
                        }
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(context.getString(R.string.recorder_discard_title)) },
            text = { Text(context.getString(R.string.recorder_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    recorder.discard()
                    resetForRerecord()
                    onDismiss()
                }) {
                    Text(
                        text = context.getString(R.string.recorder_discard),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(context.getString(R.string.action_cancel))
                }
            },
        )
    }
}

private enum class RecorderUi { Idle, Recording, Paused, Review }

@Composable
private fun RoundIconButton(
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    contentDescription: String,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.size(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            glyph()
        }
    }
}

/** mm:ss, or h:mm:ss once past an hour (which the upload cap makes practically impossible). */
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun defaultRecordingName(context: Context): String {
    val stamp = android.text.format.DateFormat.format(
        "yyyy-MM-dd HH:mm", System.currentTimeMillis()
    ).toString()
    return context.getString(R.string.recorder_default_name, stamp)
}

/**
 * A minimal wrapper over [MediaPlayer] for previewing the just-recorded file.
 *
 * Not the app's shared player: created and destroyed with the sheet, supports resume-from-pause,
 * and reports completion back so the play/stop glyph can flip.
 */
private class PreviewPlayer {
    private var player: MediaPlayer? = null
    private var sourcePath: String? = null
    var onCompletion: (() -> Unit)? = null

    fun playOrResume(file: File) {
        val existing = player
        if (existing != null && sourcePath == file.absolutePath) {
            existing.start()
            return
        }
        release()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { onCompletion?.invoke() }
            prepare()
            start()
        }
        sourcePath = file.absolutePath
    }

    fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    fun stop() {
        runCatching {
            player?.let {
                if (it.isPlaying) it.pause()
                it.seekTo(0)
            }
        }
    }

    fun release() {
        runCatching { player?.release() }
        player = null
        sourcePath = null
    }
}
