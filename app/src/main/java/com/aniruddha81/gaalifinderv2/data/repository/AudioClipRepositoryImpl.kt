package com.aniruddha81.gaalifinderv2.data.repository

import com.aniruddha81.gaalifinderv2.core.connectivity.ConnectivityMonitor
import com.aniruddha81.gaalifinderv2.core.dispatcher.IoDispatcher
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.error.AppErrorException
import com.aniruddha81.gaalifinderv2.core.error.toAppError
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.core.result.getOrNull
import com.aniruddha81.gaalifinderv2.core.result.runCatchingResult
import com.aniruddha81.gaalifinderv2.core.util.FileNames
import com.aniruddha81.gaalifinderv2.data.local.dao.AudioFileDao
import com.aniruddha81.gaalifinderv2.data.local.entity.AudioFileEntity
import com.aniruddha81.gaalifinderv2.data.mapper.toDomain
import com.aniruddha81.gaalifinderv2.data.remote.RemoteAudioDataSource
import com.aniruddha81.gaalifinderv2.data.storage.AudioFileStorage
import com.aniruddha81.gaalifinderv2.data.storage.AudioMetadataReader
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.repository.AudioClipRepository
import com.aniruddha81.gaalifinderv2.domain.repository.ImportOutcome
import com.aniruddha81.gaalifinderv2.domain.repository.ImportRequest
import com.aniruddha81.gaalifinderv2.domain.repository.SyncOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioClipRepositoryImpl @Inject constructor(
    private val dao: AudioFileDao,
    private val remote: RemoteAudioDataSource,
    private val storage: AudioFileStorage,
    private val metadataReader: AudioMetadataReader,
    private val connectivity: ConnectivityMonitor,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AudioClipRepository {

    /**
     * The database is the single source of truth. Every write below simply changes a row and
     * lets Room re-emit — the old code re-collected the whole table after each mutation, which
     * stacked up a new never-cancelled collector on every call.
     */
    override fun observeClips(): Flow<List<AudioClip>> = dao.observeAll()
        .map { entities -> entities.toDomain() }
        .catch { emit(emptyList()) }
        .flowOn(ioDispatcher)

    override suspend fun syncRemoteClips(): DataResult<SyncOutcome> = withContext(ioDispatcher) {
        if (!remote.isConfigured) return@withContext DataResult.Failure(AppError.RemoteNotConfigured)
        if (!connectivity.isCurrentlyOnline()) return@withContext DataResult.Failure(AppError.NoConnection)

        when (val listing = remote.listFiles()) {
            is DataResult.Failure -> DataResult.Failure(listing.error)
            is DataResult.Success -> {
                var downloaded = 0
                var alreadyPresent = 0
                var failed = 0

                for (file in listing.data) {
                    // One bad file must not abort the whole sync, so each is accounted for
                    // individually and the tally is reported back to the user.
                    when (downloadAndStore(file.id, file.name)) {
                        DownloadResult.Stored -> downloaded++
                        DownloadResult.AlreadyPresent -> alreadyPresent++
                        DownloadResult.Failed -> failed++
                    }
                }

                DataResult.Success(
                    SyncOutcome(
                        downloaded = downloaded,
                        alreadyPresent = alreadyPresent,
                        failed = failed,
                    )
                )
            }
        }
    }

    private suspend fun downloadAndStore(remoteId: String, remoteName: String): DownloadResult {
        return try {
            if (dao.existsByRemoteId(remoteId)) return DownloadResult.AlreadyPresent

            val bytes = remote.downloadFile(remoteId).getOrNull()
                ?: return DownloadResult.Failed

            val safeName = FileNames.sanitize(remoteName)
            val fileName = FileNames.uniqueFileName(safeName) { candidate ->
                storage.resolve(candidate).exists()
            }

            val stored = storage.save(fileName, ByteArrayInputStream(bytes)).getOrNull()
                ?: return DownloadResult.Failed

            val insertedId = dao.insert(
                AudioFileEntity(
                    fileName = fileName,
                    path = stored.path,
                    source = remoteId,
                    isNew = true,
                    durationMs = metadataReader.readDurationMs(stored.path),
                    sizeBytes = stored.sizeBytes,
                    addedAt = System.currentTimeMillis(),
                )
            )

            if (insertedId == -1L) {
                // The row lost a race with another sync; drop the orphaned file we just wrote.
                storage.delete(stored.path)
                DownloadResult.AlreadyPresent
            } else {
                DownloadResult.Stored
            }
        } catch (e: Throwable) {
            e.toAppError()
            DownloadResult.Failed
        }
    }

    override suspend fun importClip(request: ImportRequest): DataResult<ImportOutcome> =
        withContext(ioDispatcher) {
            runCatchingResult {
                val safeName = FileNames.sanitize(request.fileName)

                if (dao.existsByFileName(safeName)) {
                    return@runCatchingResult ImportOutcome.AlreadyExists(safeName)
                }
                if (request.bytes.isEmpty()) throw AppErrorException(AppError.Storage())

                val fileName = FileNames.uniqueFileName(safeName) { candidate ->
                    storage.resolve(candidate).exists()
                }

                val stored = when (val result = storage.save(fileName, ByteArrayInputStream(request.bytes))) {
                    is DataResult.Failure -> throw AppErrorException(result.error)
                    is DataResult.Success -> result.data
                }

                val entity = AudioFileEntity(
                    fileName = fileName,
                    path = stored.path,
                    source = AudioFileEntity.LOCAL_SOURCE,
                    // The user just chose this clip, so it is not "new" to them.
                    isNew = false,
                    durationMs = metadataReader.readDurationMs(stored.path),
                    sizeBytes = stored.sizeBytes,
                    addedAt = System.currentTimeMillis(),
                )

                val newId = dao.insert(entity)
                if (newId == -1L) {
                    storage.delete(stored.path)
                    ImportOutcome.AlreadyExists(fileName)
                } else {
                    ImportOutcome.Added(entity.copy(id = newId).toDomain())
                }
            }
        }

    override suspend fun deleteClip(clip: AudioClip): DataResult<Unit> = withContext(ioDispatcher) {
        runCatchingResult {
            if (!clip.isDeletable) throw AppErrorException(AppError.Unexpected())

            val entity = dao.findById(clip.id) ?: return@runCatchingResult
            // Remove the row first: an orphaned file wastes space, but an orphaned row shows the
            // user a clip that can never play.
            dao.delete(entity)
            storage.delete(entity.path)
            Unit
        }
    }

    override suspend fun renameClip(
        clipId: Long,
        newDisplayName: String,
    ): DataResult<AudioClip> = withContext(ioDispatcher) {
        runCatchingResult {
            val trimmed = newDisplayName.trim()
            if (trimmed.isEmpty()) throw AppErrorException(AppError.EmptyName)
            if (!FileNames.isValidDisplayName(trimmed)) throw AppErrorException(AppError.InvalidName)

            val entity = dao.findById(clipId) ?: throw AppErrorException(AppError.ClipFileMissing)
            val newFileName = FileNames.withExtensionOf(trimmed, entity.fileName)

            if (newFileName == entity.fileName) return@runCatchingResult entity.toDomain()
            if (dao.existsByFileNameExcluding(newFileName, clipId)) {
                throw AppErrorException(AppError.DuplicateName)
            }

            val stored = when (val result = storage.rename(entity.path, newFileName)) {
                is DataResult.Failure -> throw AppErrorException(result.error)
                is DataResult.Success -> result.data
            }

            val updated = entity.copy(fileName = newFileName, path = stored.path)
            val rows = dao.update(updated)
            if (rows == 0) {
                // Put the file back so disk and database do not drift apart.
                storage.rename(stored.path, entity.fileName)
                throw AppErrorException(AppError.Database())
            }
            updated.toDomain()
        }
    }

    override suspend fun markClipSeen(clipId: Long): DataResult<Unit> = withContext(ioDispatcher) {
        runCatchingResult { dao.markSeen(clipId); Unit }
    }

    override suspend fun backfillMissingMetadata(): DataResult<Unit> = withContext(ioDispatcher) {
        runCatchingResult {
            dao.findWithMissingMetadata().forEach { entity ->
                if (!storage.exists(entity.path)) return@forEach
                val duration = metadataReader.readDurationMs(entity.path)
                // The row's own path is authoritative — resolving by name could pick the legacy
                // copy of a clip that has since been moved.
                val size = File(entity.path).length()
                if (duration > 0 || size > 0) {
                    dao.update(
                        entity.copy(
                            durationMs = if (duration > 0) duration else entity.durationMs,
                            sizeBytes = if (size > 0) size else entity.sizeBytes,
                        )
                    )
                }
            }
        }
    }

    private enum class DownloadResult { Stored, AlreadyPresent, Failed }
}
