package com.aniruddha81.gaalifinderv2.data.repository

import com.aniruddha81.gaalifinderv2.core.connectivity.ConnectivityMonitor
import com.aniruddha81.gaalifinderv2.core.dispatcher.IoDispatcher
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.error.AppErrorException
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.core.result.getOrNull
import com.aniruddha81.gaalifinderv2.core.result.runCatchingResult
import com.aniruddha81.gaalifinderv2.core.util.FileNames
import com.aniruddha81.gaalifinderv2.data.local.dao.AudioFileDao
import com.aniruddha81.gaalifinderv2.data.mapper.toDomain
import com.aniruddha81.gaalifinderv2.data.mapper.toEntity
import com.aniruddha81.gaalifinderv2.data.remote.RemoteAudioDataSource
import com.aniruddha81.gaalifinderv2.data.remote.UploadRequest
import com.aniruddha81.gaalifinderv2.data.storage.AudioFileStorage
import com.aniruddha81.gaalifinderv2.data.storage.AudioMetadataReader
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.AuthState
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType
import com.aniruddha81.gaalifinderv2.domain.model.StorageQuota
import com.aniruddha81.gaalifinderv2.domain.model.UserProfile
import com.aniruddha81.gaalifinderv2.domain.repository.AudioClipRepository
import com.aniruddha81.gaalifinderv2.domain.repository.AuthRepository
import com.aniruddha81.gaalifinderv2.domain.repository.StorageUsage
import com.aniruddha81.gaalifinderv2.domain.repository.SyncOutcome
import com.aniruddha81.gaalifinderv2.domain.repository.UploadClipRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioClipRepositoryImpl @Inject constructor(
    private val dao: AudioFileDao,
    private val remote: RemoteAudioDataSource,
    private val storage: AudioFileStorage,
    private val metadataReader: AudioMetadataReader,
    private val connectivity: ConnectivityMonitor,
    private val auth: AuthRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AudioClipRepository {

    /**
     * The local mirror is what the UI observes. Every write below changes a row and lets Room
     * re-emit, so the grid can never drift out of step with what has actually been stored.
     */
    override fun observeClips(): Flow<List<AudioClip>> = dao.observeAll()
        .map { entities -> entities.toDomain() }
        .catch { emit(emptyList()) }
        .flowOn(ioDispatcher)

    override suspend fun syncCatalogue(): DataResult<SyncOutcome> = withContext(ioDispatcher) {
        if (!remote.isConfigured) return@withContext DataResult.Failure(AppError.RemoteNotConfigured)
        if (!connectivity.isCurrentlyOnline()) return@withContext DataResult.Failure(AppError.NoConnection)

        when (val listing = remote.listClips()) {
            is DataResult.Failure -> DataResult.Failure(listing.error)
            is DataResult.Success -> runCatchingResult {
                val known = dao.findAll().associateBy { it.documentId }

                // Reactions are per-account, so a guest simply gets none rather than the
                // previous user's. A failed read degrades to "no reactions" instead of failing
                // the whole sync — the catalogue is still worth showing.
                val userId = auth.authState.value.userOrNull?.id
                val reactions = userId
                    ?.let { remote.reactionsOf(it).getOrNull() }
                    ?: emptyMap()

                val rows = listing.data.map { clip ->
                    val row = clip.toEntity(reactions[clip.documentId] ?: ReactionType.None)
                    // Re-adopt a cached file that is already on disk under this file id, so an
                    // upgrading or reinstalling user does not re-download what they have.
                    row.copy(cachedPath = storage.cachedPathFor(clip.fileId, clip.fileName))
                }

                dao.replaceCatalogue(rows)

                SyncOutcome(
                    total = rows.size,
                    added = rows.count { it.documentId !in known },
                )
            }
        }
    }

    /**
     * Enforces both limits before uploading, in the order the spec requires: an oversized file
     * is rejected on its own terms and never consumes a quota lookup.
     */
    override suspend fun uploadClip(request: UploadClipRequest): DataResult<AudioClip> =
        withContext(ioDispatcher) {
            runCatchingResult {
                if (!remote.isConfigured) throw AppErrorException(AppError.RemoteNotConfigured)
                if (request.bytes.isEmpty()) throw AppErrorException(AppError.Storage())

                // 1. Per-file cap. Checked first and independently of the total quota.
                if (request.sizeBytes > StorageQuota.MAX_FILE_BYTES) {
                    throw AppErrorException(AppError.FileTooLarge(request.sizeBytes))
                }

                // 2. Total quota, branching on the user's tier.
                val usage = when (val result = storageUsage(request.uploaderId)) {
                    is DataResult.Failure -> throw AppErrorException(result.error)
                    is DataResult.Success -> result.data
                }
                if (!usage.hasRoomFor(request.sizeBytes)) {
                    throw AppErrorException(
                        AppError.QuotaExceeded(
                            usedBytes = usage.usedBytes,
                            limitBytes = usage.limitBytes,
                            isPremium = usage.isPremium,
                        )
                    )
                }

                // 3. Upload. The server re-validates both limits and can still reject this.
                val uploaded = when (
                    val result = remote.upload(
                        UploadRequest(
                            fileName = FileNames.sanitize(request.fileName),
                            bytes = request.bytes,
                            uploaderId = request.uploaderId,
                            uploaderName = request.uploaderName,
                        )
                    )
                ) {
                    is DataResult.Failure -> throw AppErrorException(result.error)
                    is DataResult.Success -> result.data
                }

                // The uploader already has the bytes, so seed the cache rather than making them
                // download their own clip back to play it.
                val cachedPath = storage
                    .cache(uploaded.fileId, uploaded.fileName, request.bytes)
                    .getOrNull()

                val entity = uploaded
                    .toEntity(ReactionType.None)
                    .copy(
                        // Their own upload is not "new" to them.
                        isNew = false,
                        cachedPath = cachedPath,
                        durationMs = cachedPath?.let { metadataReader.readDurationMs(it) } ?: 0,
                    )

                dao.upsertAll(listOf(entity))
                entity.toDomain()
            }
        }

    override suspend fun storageUsage(userId: String): DataResult<StorageUsage> =
        withContext(ioDispatcher) {
            runCatchingResult {
                val used = when (val result = remote.usedStorageBytes(userId)) {
                    is DataResult.Failure -> throw AppErrorException(result.error)
                    is DataResult.Success -> result.data
                }

                // The plan record is the server's, not the client's, to decide — this only
                // reads whatever an admin has already set.
                val profile = (auth.authState.value as? AuthState.SignedIn)
                    ?.takeIf { it.user.id == userId }
                    ?.profile
                    ?: UserProfile.free(userId)

                StorageUsage(
                    usedBytes = used,
                    limitBytes = profile.totalStorageLimitBytes,
                    isPremium = profile.isPremium,
                )
            }
        }

    override suspend fun deleteClip(clip: AudioClip): DataResult<Unit> = withContext(ioDispatcher) {
        runCatchingResult {
            val userId = auth.authState.value.userOrNull?.id
                ?: throw AppErrorException(AppError.NotSignedIn)
            if (!clip.isDeletableBy(userId)) throw AppErrorException(AppError.Unexpected())

            when (val result = remote.deleteClip(clip.id, clip.fileId)) {
                is DataResult.Failure -> throw AppErrorException(result.error)
                is DataResult.Success -> Unit
            }

            dao.deleteById(clip.id)
            clip.cachedPath?.let { storage.delete(it) }
            Unit
        }
    }

    /**
     * Returns a local path the player can open, downloading the audio on first play.
     *
     * Streaming straight from the Appwrite URL was the alternative, but the bucket is not
     * public-by-URL — reads go through the session — so the bytes have to come down through the
     * SDK either way. Caching them means the second play is instant and works offline.
     */
    override suspend fun ensurePlayable(clip: AudioClip): DataResult<String> =
        withContext(ioDispatcher) {
            runCatchingResult {
                val existing = clip.cachedPath?.takeIf { storage.exists(it) }
                if (existing != null) return@runCatchingResult existing

                if (!connectivity.isCurrentlyOnline()) {
                    throw AppErrorException(AppError.NoConnection)
                }

                val bytes = when (val result = remote.downloadFile(clip.fileId)) {
                    is DataResult.Failure -> throw AppErrorException(result.error)
                    is DataResult.Success -> result.data
                }

                val path = when (val result = storage.cache(clip.fileId, clip.fileName, bytes)) {
                    is DataResult.Failure -> throw AppErrorException(result.error)
                    is DataResult.Success -> result.data
                }

                // Duration is only knowable once the file is on disk, so it is recorded here
                // rather than guessed at sync time.
                dao.setCached(clip.id, path, metadataReader.readDurationMs(path))
                path
            }
        }

    /**
     * Applies a reaction and re-reads the authoritative counts.
     *
     * The local row is updated optimistically so the tap feels instant, then reconciled against
     * whatever the count-sync Function computed — the client never does its own arithmetic on
     * `likeCount`, which is what makes concurrent reactions safe.
     */
    override suspend fun react(
        clip: AudioClip,
        userId: String,
        tapped: ReactionType,
    ): DataResult<AudioClip> = withContext(ioDispatcher) {
        runCatchingResult {
            val target = clip.myReaction.toggledBy(tapped)
            val optimistic = clip.withOptimisticReaction(target)

            dao.setReaction(
                id = clip.id,
                reaction = target.wireValue,
                likeCount = optimistic.likeCount,
                dislikeCount = optimistic.dislikeCount,
            )

            when (val result = remote.setReaction(clip.id, userId, target)) {
                is DataResult.Failure -> {
                    // Put the row back the way it was rather than leaving a reaction the server
                    // never accepted.
                    dao.setReaction(
                        id = clip.id,
                        reaction = clip.myReaction.wireValue,
                        likeCount = clip.likeCount,
                        dislikeCount = clip.dislikeCount,
                    )
                    throw AppErrorException(result.error)
                }

                is DataResult.Success -> Unit
            }

            // The Function is what owns the counts; this settles the row on its result. If the
            // read fails the optimistic values simply stand until the next sync.
            val settled = remote.counts(clip.id).getOrNull()
            if (settled != null) {
                dao.setReaction(
                    id = clip.id,
                    reaction = target.wireValue,
                    likeCount = settled.first,
                    dislikeCount = settled.second,
                )
                optimistic.copy(likeCount = settled.first, dislikeCount = settled.second)
            } else {
                optimistic
            }
        }
    }

    override suspend fun markClipSeen(clipId: String): DataResult<Unit> =
        withContext(ioDispatcher) {
            runCatchingResult { dao.markSeen(clipId); Unit }
        }

    override suspend fun clearLocalReactions() {
        withContext(ioDispatcher) { runCatching { dao.clearAllReactions() } }
    }
}

/**
 * The counts this clip would have if [target] were applied.
 *
 * Purely for the optimistic update — the server-side Function remains the authority, and this
 * is overwritten as soon as it reports back.
 */
private fun AudioClip.withOptimisticReaction(target: ReactionType): AudioClip {
    val likeDelta = (if (target == ReactionType.Like) 1 else 0) -
        (if (myReaction == ReactionType.Like) 1 else 0)
    val dislikeDelta = (if (target == ReactionType.Dislike) 1 else 0) -
        (if (myReaction == ReactionType.Dislike) 1 else 0)

    return copy(
        myReaction = target,
        likeCount = (likeCount + likeDelta).coerceAtLeast(0),
        dislikeCount = (dislikeCount + dislikeDelta).coerceAtLeast(0),
    )
}
