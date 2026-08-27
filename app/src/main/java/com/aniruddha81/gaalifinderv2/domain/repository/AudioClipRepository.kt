package com.aniruddha81.gaalifinderv2.domain.repository

import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType
import kotlinx.coroutines.flow.Flow

/**
 * The single way the app reads or changes the shared clip catalogue.
 *
 * Reads are exposed as a [Flow] over the local mirror, so the grid renders instantly and
 * offline while Appwrite stays authoritative. Writes return [DataResult] because each one can
 * fail in a way the user needs to hear about.
 */
interface AudioClipRepository {

    /** The whole catalogue, re-emitting on every change. */
    fun observeClips(): Flow<List<AudioClip>>

    /** Pulls the current catalogue — and, when signed in, this user's reactions — from Appwrite. */
    suspend fun syncCatalogue(): DataResult<SyncOutcome>

    /**
     * Checks the limits, then uploads to Appwrite Storage and registers the metadata.
     *
     * Both the per-file cap and the total-quota check happen here before a byte is sent, so an
     * over-quota upload costs nothing. The server re-validates independently.
     */
    suspend fun uploadClip(request: UploadClipRequest): DataResult<AudioClip>

    /** How much of their allowance this user has used, for the quota UI. */
    suspend fun storageUsage(userId: String): DataResult<StorageUsage>

    /** Removes a clip from Appwrite and the local mirror. Only the uploader may do this. */
    suspend fun deleteClip(clip: AudioClip): DataResult<Unit>

    /**
     * Makes the audio available to the player, downloading and caching it on first use.
     * Returns the local path to play from.
     */
    suspend fun ensurePlayable(clip: AudioClip): DataResult<String>

    /** Applies a like/dislike toggle for [userId], writing through to Appwrite. */
    suspend fun react(
        clip: AudioClip,
        userId: String,
        tapped: ReactionType,
    ): DataResult<AudioClip>

    /** Clears the "new" badge once the user has actually heard the clip. */
    suspend fun markClipSeen(clipId: String): DataResult<Unit>

    /** Drops every cached reaction marker, since they belong to the account that just left. */
    suspend fun clearLocalReactions()
}

/** A file the user picked, handed over as bytes so the content URI can be released immediately. */
data class UploadClipRequest(
    val fileName: String,
    val bytes: ByteArray,
    val uploaderId: String,
    val uploaderName: String,
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    // ByteArray uses identity equality, which would silently break `==` on this data class.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is UploadClipRequest &&
                fileName == other.fileName &&
                uploaderId == other.uploaderId &&
                uploaderName == other.uploaderName &&
                bytes.contentEquals(other.bytes)
            )

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + uploaderId.hashCode()
        result = 31 * result + uploaderName.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/** Where a user stands against their allowance. */
data class StorageUsage(
    val usedBytes: Long,
    val limitBytes: Long,
    val isPremium: Boolean,
) {
    val remainingBytes: Long get() = (limitBytes - usedBytes).coerceAtLeast(0)

    fun hasRoomFor(sizeBytes: Long): Boolean = usedBytes + sizeBytes <= limitBytes

    val fractionUsed: Float
        get() = if (limitBytes <= 0) 0f else (usedBytes.toFloat() / limitBytes).coerceIn(0f, 1f)
}

/** What a catalogue sync actually did, so the UI can report it precisely. */
data class SyncOutcome(
    val total: Int,
    val added: Int,
) {
    val hasNewClips: Boolean get() = added > 0
}
