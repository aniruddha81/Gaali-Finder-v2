package com.aniruddha81.gaalifinderv2.ui.home

import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType
import com.aniruddha81.gaalifinderv2.domain.model.StorageQuota
import com.aniruddha81.gaalifinderv2.domain.repository.AudioClipRepository
import com.aniruddha81.gaalifinderv2.domain.repository.StorageUsage
import com.aniruddha81.gaalifinderv2.domain.repository.SyncOutcome
import com.aniruddha81.gaalifinderv2.domain.repository.UploadClipRequest
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

    var syncResult: DataResult<SyncOutcome> = DataResult.Success(SyncOutcome(0, 0))
    var uploadResult: DataResult<AudioClip>? = null
    var deleteResult: DataResult<Unit> = DataResult.Success(Unit)
    var reactResult: DataResult<AudioClip>? = null
    var playableResult: DataResult<String>? = null
    var usageResult: DataResult<StorageUsage> = DataResult.Success(
        StorageUsage(
            usedBytes = 0,
            limitBytes = StorageQuota.FREE_TOTAL_BYTES,
            isPremium = false,
        )
    )

    val markSeenCalls = mutableListOf<String>()
    val uploadRequests = mutableListOf<UploadClipRequest>()
    val reactions = mutableListOf<Pair<String, ReactionType>>()
    var syncCount = 0
    var clearedReactions = 0

    override fun observeClips(): Flow<List<AudioClip>> = clips

    override suspend fun syncCatalogue(): DataResult<SyncOutcome> {
        syncCount++
        return syncResult
    }

    override suspend fun uploadClip(request: UploadClipRequest): DataResult<AudioClip> {
        uploadRequests += request
        return uploadResult ?: DataResult.Success(
            AudioClip(
                id = "doc${clips.value.size + 1}",
                fileId = "file${clips.value.size + 1}",
                fileName = request.fileName,
                uploaderId = request.uploaderId,
                uploaderName = request.uploaderName,
                isNew = false,
                durationMs = 1_000,
                sizeBytes = request.sizeBytes,
                createdAt = 0,
            )
        )
    }

    override suspend fun storageUsage(userId: String): DataResult<StorageUsage> = usageResult

    override suspend fun deleteClip(clip: AudioClip): DataResult<Unit> = deleteResult

    override suspend fun ensurePlayable(clip: AudioClip): DataResult<String> =
        playableResult ?: DataResult.Success(clip.cachedPath ?: "/clips/${clip.fileId}.mp3")

    override suspend fun react(
        clip: AudioClip,
        userId: String,
        tapped: ReactionType,
    ): DataResult<AudioClip> {
        reactions += clip.id to tapped
        return reactResult
            ?: DataResult.Success(clip.copy(myReaction = clip.myReaction.toggledBy(tapped)))
    }

    override suspend fun markClipSeen(clipId: String): DataResult<Unit> {
        markSeenCalls += clipId
        return DataResult.Success(Unit)
    }

    override suspend fun clearLocalReactions() {
        clearedReactions++
    }
}
