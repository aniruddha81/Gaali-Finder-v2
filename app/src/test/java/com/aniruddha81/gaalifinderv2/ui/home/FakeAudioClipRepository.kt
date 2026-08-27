package com.aniruddha81.gaalifinderv2.ui.home

import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.ClipOrigin
import com.aniruddha81.gaalifinderv2.domain.repository.AudioClipRepository
import com.aniruddha81.gaalifinderv2.domain.repository.ImportOutcome
import com.aniruddha81.gaalifinderv2.domain.repository.ImportRequest
import com.aniruddha81.gaalifinderv2.domain.repository.SyncOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory stand-in for the real repository.
 *
 * Every method can be told to fail, which is what makes the ViewModel's error paths testable
 * without a database, filesystem or network.
 */
class FakeAudioClipRepository : AudioClipRepository {

    val clips = MutableStateFlow<List<AudioClip>>(emptyList())

    var syncResult: DataResult<SyncOutcome> = DataResult.Success(SyncOutcome(0, 0, 0))
    var importResult: DataResult<ImportOutcome>? = null
    var deleteResult: DataResult<Unit> = DataResult.Success(Unit)
    var renameResult: DataResult<AudioClip>? = null

    var markSeenCalls = mutableListOf<Long>()
    var syncCount = 0

    override fun observeClips(): Flow<List<AudioClip>> = clips

    override suspend fun syncRemoteClips(): DataResult<SyncOutcome> {
        syncCount++
        return syncResult
    }

    override suspend fun importClip(request: ImportRequest): DataResult<ImportOutcome> =
        importResult ?: DataResult.Success(
            ImportOutcome.Added(
                AudioClip(
                    id = clips.value.size + 1L,
                    fileName = request.fileName,
                    filePath = "/clips/${request.fileName}",
                    origin = ClipOrigin.Local,
                    isNew = false,
                    durationMs = 1_000,
                    sizeBytes = request.bytes.size.toLong(),
                    addedAt = 0,
                )
            )
        )

    override suspend fun deleteClip(clip: AudioClip): DataResult<Unit> = deleteResult

    override suspend fun renameClip(clipId: Long, newDisplayName: String): DataResult<AudioClip> =
        renameResult ?: DataResult.Failure(AppError.Unexpected())

    override suspend fun markClipSeen(clipId: Long): DataResult<Unit> {
        markSeenCalls += clipId
        return DataResult.Success(Unit)
    }

    override suspend fun backfillMissingMetadata(): DataResult<Unit> = DataResult.Success(Unit)
}
