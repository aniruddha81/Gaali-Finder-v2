package com.aniruddha81.gaalifinderv2.domain.model

/**
 * A playable sound clip, as the rest of the app thinks about it.
 *
 * This is deliberately free of Room and Appwrite types: the storage schema can change without
 * the UI noticing, and the UI can be tested without a database.
 *
 * Every clip now originates from the shared Appwrite catalogue — [id] is the `audio_metadata`
 * document id, and [fileId] the Storage file behind it. [cachedPath] is only ever a local copy
 * kept so playback works offline and does not re-download on every tap; it is a cache, never a
 * source of truth, and is null until the clip has been played once.
 */
data class AudioClip(
    /** The `audio_metadata` document id. Stable across devices, unlike the old row id. */
    val id: String,
    /** Appwrite Storage file id, used to stream or download the audio. */
    val fileId: String,
    /** File name including extension — e.g. `hello.mp3`. */
    val fileName: String,
    val uploaderId: String,
    val uploaderName: String,
    val isNew: Boolean,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAt: Long,
    val likeCount: Int = 0,
    val dislikeCount: Int = 0,
    /** This user's own reaction, or [ReactionType.None] when they have not reacted or are a guest. */
    val myReaction: ReactionType = ReactionType.None,
    /** Local cache path, present once the audio has been fetched at least once. */
    val cachedPath: String? = null,
) {
    /** Name shown to the user: the file name without its extension. */
    val displayName: String get() = fileName.substringBeforeLast('.', fileName)

    /** Drives the "Most popular" sort. Net score, so a divisive clip ranks below a loved one. */
    val netScore: Int get() = likeCount - dislikeCount

    val hasKnownDuration: Boolean get() = durationMs > 0

    val isDownloaded: Boolean get() = cachedPath != null

    /** Only the person who uploaded a clip may remove it. */
    fun isDeletableBy(userId: String?): Boolean = userId != null && userId == uploaderId
}

/** Which subset of the catalogue the user is currently looking at. */
enum class ClipFilter {
    All,
    New,
    Downloaded,
    MyClips,
}

/** How the catalogue is ordered. */
enum class ClipSort {
    NameAsc,
    RecentFirst,
    LongestFirst,
    MostPopular,
}
