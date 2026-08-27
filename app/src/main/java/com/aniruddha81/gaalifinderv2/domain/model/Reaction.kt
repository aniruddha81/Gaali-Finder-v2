package com.aniruddha81.gaalifinderv2.domain.model

/**
 * A user's stance on one clip.
 *
 * Modelled as a single value rather than two booleans, which is what makes "never liked and
 * disliked at once" a property of the type instead of an invariant somebody has to remember.
 * [None] is represented by the absence of an `audio_reactions` document, not a stored value.
 */
enum class ReactionType {
    Like,
    Dislike,
    None;

    /** The wire value stored in the `type` attribute; [None] never reaches the server. */
    val wireValue: String?
        get() = when (this) {
            Like -> "like"
            Dislike -> "dislike"
            None -> null
        }

    /**
     * What tapping [tapped] does from this state.
     *
     * Tapping the active reaction clears it; tapping the other one switches straight over.
     */
    fun toggledBy(tapped: ReactionType): ReactionType =
        if (this == tapped) None else tapped

    companion object {
        fun fromWire(value: String?): ReactionType = when (value?.lowercase()) {
            "like" -> Like
            "dislike" -> Dislike
            else -> None
        }
    }
}
