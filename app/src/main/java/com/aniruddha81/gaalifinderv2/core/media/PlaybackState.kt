package com.aniruddha81.gaalifinderv2.core.media

/**
 * What the player is doing right now.
 *
 * The screen renders straight off this, so exactly one clip can ever appear to be playing —
 * previously each card tracked its own boolean and they could disagree.
 *
 * [clipId] is the Appwrite `audio_metadata` document id. It is a String rather than the old row
 * id because clips are now identified by the server, and hashing one into a Long to fit the old
 * signature would let two clips collide into looking like the same one.
 */
data class PlaybackState(
    val clipId: String? = null,
    val status: Status = Status.Idle,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
) {
    enum class Status { Idle, Preparing, Playing }

    val isActive: Boolean get() = status != Status.Idle

    fun isPlaying(id: String): Boolean = clipId == id && status == Status.Playing

    fun isPreparing(id: String): Boolean = clipId == id && status == Status.Preparing

    /** 0f..1f, or 0f while the duration is still unknown. */
    fun progressFor(id: String): Float = when {
        clipId != id || durationMs <= 0 -> 0f
        else -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    companion object {
        val Idle = PlaybackState()
    }
}
