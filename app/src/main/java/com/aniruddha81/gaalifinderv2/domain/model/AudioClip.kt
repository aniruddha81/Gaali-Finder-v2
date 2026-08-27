package com.aniruddha81.gaalifinderv2.domain.model

/**
 * A playable sound clip, as the rest of the app thinks about it.
 *
 * This is deliberately free of Room and Appwrite types: the storage schema can change without
 * the UI noticing, and the UI can be tested without a database.
 */
data class AudioClip(
    val id: Long,
    /** File name on disk, extension included — e.g. `hello.mp3`. */
    val fileName: String,
    val filePath: String,
    val origin: ClipOrigin,
    val isNew: Boolean,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedAt: Long,
) {
    /** Name shown to the user: the file name without its extension. */
    val displayName: String get() = fileName.substringBeforeLast('.', fileName)

    /** Only clips the user added themselves may be deleted; synced ones would just come back. */
    val isDeletable: Boolean get() = origin is ClipOrigin.Local

    val hasKnownDuration: Boolean get() = durationMs > 0
}

/** Where a clip came from, which decides whether the user is allowed to remove it. */
sealed interface ClipOrigin {
    /** Imported from the device by the user. */
    data object Local : ClipOrigin

    /** Downloaded from the shared Appwrite catalogue; [remoteId] is its bucket file id. */
    data class Remote(val remoteId: String) : ClipOrigin
}

/** Which subset of the library the user is currently looking at. */
enum class ClipFilter {
    All,
    New,
    Downloaded,
    MyClips,
}

/** How the library is ordered. */
enum class ClipSort {
    NameAsc,
    RecentFirst,
    LongestFirst,
}
