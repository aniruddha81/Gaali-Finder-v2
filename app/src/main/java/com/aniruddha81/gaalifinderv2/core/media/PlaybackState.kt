package com.aniruddha81.gaalifinderv2.core.media

/**
 * What the player is doing right now.
 *
 * The screen renders straight off this, so exactly one clip can ever appear to be playing —
 * previously each card tracked its own boolean and they could disagree.
 */
data class PlaybackState(
    val clipId: Long? = null,
    val status: Status = Status.Idle,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
) {
    enum class Status { Idle, Preparing, Playing }

    val isActive: Boolean get() = status != Status.Idle

    fun isPlaying(id: Long): Boolean = clipId == id && status == Status.Playing

    fun isPreparing(id: Long): Boolean = clipId == id && status == Status.Preparing

    /** 0f..1f, or 0f while the duration is still unknown. */
    fun progressFor(id: Long): Float = when {
        clipId != id || durationMs <= 0 -> 0f
        else -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    companion object {
        val Idle = PlaybackState()
    }
}
