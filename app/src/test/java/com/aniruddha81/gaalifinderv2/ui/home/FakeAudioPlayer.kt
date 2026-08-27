package com.aniruddha81.gaalifinderv2.ui.home

import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.media.AudioPlayer
import com.aniruddha81.gaalifinderv2.core.media.PlaybackState
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Records what was asked of the player and can be told to fail, with no MediaPlayer involved. */
class FakeAudioPlayer : AudioPlayer {

    private val _state = MutableStateFlow(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state

    var nextResult: DataResult<Unit> = DataResult.Success(Unit)
    val toggled = mutableListOf<Long>()
    var stopCount = 0

    override suspend fun toggle(clipId: Long, filePath: String): DataResult<Unit> {
        toggled += clipId
        if (nextResult is DataResult.Success) {
            _state.value = PlaybackState(clipId, PlaybackState.Status.Playing)
        }
        return nextResult
    }

    override suspend fun play(clipId: Long, filePath: String): DataResult<Unit> =
        toggle(clipId, filePath)

    override suspend fun stop() {
        stopCount++
        _state.value = PlaybackState.Idle
    }

    override fun stopBlocking() {
        stopCount++
        _state.value = PlaybackState.Idle
    }

    fun failWith(error: AppError) {
        nextResult = DataResult.Failure(error)
    }
}
