package com.aniruddha81.gaalifinderv2.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.core.content.getSystemService
import com.aniruddha81.gaalifinderv2.core.dispatcher.ApplicationScope
import com.aniruddha81.gaalifinderv2.core.dispatcher.IoDispatcher
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playback, as the rest of the app needs it.
 *
 * An interface rather than a bare class so the ViewModel can be tested against a fake instead
 * of a real [MediaPlayer] and an Android `Context`.
 */
interface AudioPlayer {
    val state: StateFlow<PlaybackState>

    /**
     * Starts [clipId] from [filePath], replacing whatever was playing.
     *
     * There is no `toggle` here on purpose: the caller has to fetch the audio before it can
     * supply a path, so it must decide "is this already playing?" *before* that fetch — a
     * toggle at this level would download a clip only to immediately stop it.
     */
    suspend fun play(clipId: String, filePath: String): DataResult<Unit>

    suspend fun stop()

    /**
     * Stop from a non-suspending teardown callback such as `ViewModel.onCleared`, where the
     * caller's own scope has already been cancelled and could not run a suspending stop.
     */
    fun stopBlocking()
}

/**
 * Owns the one [MediaPlayer] the app uses.
 *
 * The previous version held the player in `rememberSaveable` inside a composable, which both
 * leaked it across configuration changes and asked Compose to bundle a non-parcelable object.
 * Keeping it in an injected singleton means playback survives recomposition, only one clip can
 * play at a time, and the player is always released on a real lifecycle boundary.
 */
@Singleton
class AudioPlayerController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationScope private val scope: CoroutineScope,
) : AudioPlayer {

    private val _state = MutableStateFlow(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Serialises every player transition — MediaPlayer throws if its states interleave. */
    private val mutex = Mutex()

    private var player: MediaPlayer? = null
    private var progressJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    private val audioManager: AudioManager?
        get() = context.getSystemService()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                -> scope.launch { stop() }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                runCatching { player?.setVolume(DUCK_VOLUME, DUCK_VOLUME) }

            AudioManager.AUDIOFOCUS_GAIN ->
                runCatching { player?.setVolume(FULL_VOLUME, FULL_VOLUME) }
        }
    }

    override suspend fun play(clipId: String, filePath: String): DataResult<Unit> = mutex.withLock {
        releaseInternal()

        if (!File(filePath).exists()) {
            _state.value = PlaybackState.Idle
            return DataResult.Failure(AppError.ClipFileMissing)
        }

        _state.value = PlaybackState(clipId = clipId, status = PlaybackState.Status.Preparing)

        val prepared = runCatching {
            withContext(ioDispatcher) {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(filePath)
                    prepare()
                }
            }
        }.getOrElse { error ->
            _state.value = PlaybackState.Idle
            return DataResult.Failure(AppError.Playback(error))
        }

        if (!requestAudioFocus()) {
            runCatching { prepared.release() }
            _state.value = PlaybackState.Idle
            return DataResult.Failure(AppError.Playback())
        }

        prepared.setOnCompletionListener { scope.launch { stop() } }
        prepared.setOnErrorListener { _, _, _ ->
            scope.launch { stop() }
            true // handled: prevents MediaPlayer from also firing onCompletion
        }

        return runCatching {
            prepared.start()
            player = prepared
            _state.value = PlaybackState(
                clipId = clipId,
                status = PlaybackState.Status.Playing,
                positionMs = 0,
                durationMs = prepared.duration.coerceAtLeast(0),
            )
            startProgressUpdates()
            DataResult.Success(Unit)
        }.getOrElse { error ->
            runCatching { prepared.release() }
            player = null
            abandonAudioFocus()
            _state.value = PlaybackState.Idle
            DataResult.Failure(AppError.Playback(error))
        }
    }

    override suspend fun stop() = mutex.withLock {
        releaseInternal()
        _state.value = PlaybackState.Idle
    }

    override fun stopBlocking() {
        scope.launch { stop() }
    }

    private fun releaseInternal() {
        progressJob?.cancel()
        progressJob = null
        player?.let { active ->
            runCatching {
                active.setOnCompletionListener(null)
                active.setOnErrorListener(null)
                if (active.isPlaying) active.stop()
            }
            runCatching { active.release() }
        }
        player = null
        abandonAudioFocus()
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                val current = player ?: break
                val position = runCatching {
                    if (current.isPlaying) current.currentPosition else null
                }.getOrNull() ?: break
                _state.update { it.copy(positionMs = position) }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request

        return runCatching {
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(true)
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { request ->
            runCatching { manager.abandonAudioFocusRequest(request) }
        }
        focusRequest = null
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 40L
        const val DUCK_VOLUME = 0.25f
        const val FULL_VOLUME = 1.0f
    }
}
