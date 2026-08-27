package com.aniruddha81.gaalifinderv2.domain.repository

import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import kotlinx.coroutines.flow.Flow

/**
 * The single way the app reads or changes the clip library.
 *
 * Reads are exposed as a [Flow] so the database stays the one source of truth — callers observe
 * it rather than re-fetching after every write. Writes return [DataResult] because each one can
 * fail in a way the user needs to hear about.
 */
interface AudioClipRepository {

    /** The whole library, newest schema state, re-emitting on every change. */
    fun observeClips(): Flow<List<AudioClip>>

    /** Pulls any clips added to the shared catalogue since the last sync. */
    suspend fun syncRemoteClips(): DataResult<SyncOutcome>

    /** Copies a picked file into app storage and registers it. */
    suspend fun importClip(request: ImportRequest): DataResult<ImportOutcome>

    /** Removes a clip from both the database and disk. Only local clips may be deleted. */
    suspend fun deleteClip(clip: AudioClip): DataResult<Unit>

    /** Renames the file on disk and the row that points at it, as one operation. */
    suspend fun renameClip(clipId: Long, newDisplayName: String): DataResult<AudioClip>

    /** Clears the "new" badge once the user has actually heard the clip. */
    suspend fun markClipSeen(clipId: Long): DataResult<Unit>

    /** Fills in duration/size for rows saved before those columns existed. */
    suspend fun backfillMissingMetadata(): DataResult<Unit>
}

/** A file the user picked, handed over as bytes so the content URI can be released immediately. */
data class ImportRequest(
    val fileName: String,
    val bytes: ByteArray,
) {
    // ByteArray uses identity equality, which would silently break `==` on this data class.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ImportRequest && fileName == other.fileName && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
}

/** Why a single import did or did not add a clip. */
sealed interface ImportOutcome {
    data class Added(val clip: AudioClip) : ImportOutcome
    data class AlreadyExists(val fileName: String) : ImportOutcome
}

/** What a catalogue sync actually did, so the UI can report it precisely. */
data class SyncOutcome(
    val downloaded: Int,
    val alreadyPresent: Int,
    val failed: Int,
) {
    val hasNewClips: Boolean get() = downloaded > 0
}
