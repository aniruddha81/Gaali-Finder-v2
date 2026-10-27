package com.aniruddha81.gaalifinderv2.data.remote

import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType

/** One clip as the shared catalogue describes it. */
data class RemoteAudioClip(
    val documentId: String,
    val fileId: String,
    val fileName: String,
    val uploaderId: String,
    val uploaderName: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val likeCount: Int,
    val dislikeCount: Int,
)

/** What one upload needs to know about itself. */
data class UploadRequest(
    val fileName: String,
    val bytes: ByteArray,
    val uploaderId: String,
    val uploaderName: String,
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    // ByteArray uses identity equality, which would silently break `==` on this data class.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is UploadRequest &&
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

/** Read and write access to the shared clip catalogue. */
interface RemoteAudioDataSource {
    /** True when this build knows where the API is at all. */
    val isConfigured: Boolean

    suspend fun listClips(): DataResult<List<RemoteAudioClip>>

    suspend fun downloadFile(fileId: String): DataResult<ByteArray>

    /** Uploads the audio. The server enforces the size cap and the quota in the same step. */
    suspend fun upload(request: UploadRequest): DataResult<RemoteAudioClip>

    /** Removes the clip and its audio file. A clip that is already gone counts as success. */
    suspend fun deleteClip(documentId: String, fileId: String): DataResult<Unit>

    /** Total bytes this user has already stored. */
    suspend fun usedStorageBytes(userId: String): DataResult<Long>

    /** Every reaction this user currently holds, keyed by clip id. */
    suspend fun reactionsOf(userId: String): DataResult<Map<String, ReactionType>>

    /**
     * Moves this user's reaction on one clip to [target]. Returns the clip's like and dislike
     * counts as the server now holds them, so the UI can settle on the truth.
     */
    suspend fun setReaction(
        audioId: String,
        userId: String,
        target: ReactionType,
    ): DataResult<Pair<Int, Int>>
}
