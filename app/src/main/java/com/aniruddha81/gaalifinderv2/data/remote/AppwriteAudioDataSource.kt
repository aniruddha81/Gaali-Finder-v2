package com.aniruddha81.gaalifinderv2.data.remote

import com.aniruddha81.gaalifinderv2.core.dispatcher.IoDispatcher
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.error.AppErrorException
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.core.result.runCatchingResult
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType
import io.appwrite.Client
import io.appwrite.ID
import io.appwrite.Permission
import io.appwrite.Query
import io.appwrite.Role
import io.appwrite.exceptions.AppwriteException
import io.appwrite.models.InputFile
import io.appwrite.services.Databases
import io.appwrite.services.Storage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

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
    /** True when this build has credentials to reach the catalogue at all. */
    val isConfigured: Boolean

    suspend fun listClips(): DataResult<List<RemoteAudioClip>>

    suspend fun downloadFile(fileId: String): DataResult<ByteArray>

    /** Uploads the audio and registers it in `audio_metadata`, as one operation. */
    suspend fun upload(request: UploadRequest): DataResult<RemoteAudioClip>

    /** Removes both the metadata document and the Storage file. */
    suspend fun deleteClip(documentId: String, fileId: String): DataResult<Unit>

    /** Total bytes this user has already stored, summed from their own metadata documents. */
    suspend fun usedStorageBytes(userId: String): DataResult<Long>

    /** Every reaction this user currently holds, keyed by clip document id. */
    suspend fun reactionsOf(userId: String): DataResult<Map<String, ReactionType>>

    /** Moves this user's reaction on one clip to [target], creating or deleting as needed. */
    suspend fun setReaction(
        audioId: String,
        userId: String,
        target: ReactionType,
    ): DataResult<Unit>

    /** Re-reads the counts a server Function maintains, so the UI can settle on the truth. */
    suspend fun counts(documentId: String): DataResult<Pair<Int, Int>>
}

@Singleton
class AppwriteAudioDataSource @Inject constructor(
    private val client: Client,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : RemoteAudioDataSource {

    private val storage by lazy { Storage(client) }
    private val databases by lazy { Databases(client) }

    override val isConfigured: Boolean get() = AppwriteConfig.isConfigured

    override suspend fun listClips(): DataResult<List<RemoteAudioClip>> =
        withContext(ioDispatcher) {
            runCatchingResult {
                ensureConfigured()
                // Newest first, and paged: the default page size is 25, which would silently
                // truncate the catalogue as soon as it grew past that.
                val collected = mutableListOf<RemoteAudioClip>()
                var offset = 0

                while (true) {
                    val page = databases.listDocuments(
                        databaseId = AppwriteConfig.databaseId,
                        collectionId = AppwriteConfig.audioMetadataCollectionId,
                        queries = listOf(
                            // Appwrite gives every document a built-in $createdAt; sorting on it
                            // avoids maintaining a duplicate custom attribute of our own.
                            Query.orderDesc("\$createdAt"),
                            Query.limit(PAGE_SIZE),
                            Query.offset(offset),
                        ),
                    )

                    // A malformed row must not abort the whole listing.
                    collected += page.documents.mapNotNull { it.toRemoteAudioClip() }

                    offset += PAGE_SIZE
                    if (page.documents.size < PAGE_SIZE || offset >= MAX_CATALOGUE_SIZE) break
                }

                collected
            }
        }

    override suspend fun downloadFile(fileId: String): DataResult<ByteArray> =
        withContext(ioDispatcher) {
            runCatchingResult {
                ensureConfigured()
                storage.getFileView(AppwriteConfig.bucketId, fileId)
            }
        }

    /**
     * Uploads the bytes, then writes the metadata document.
     *
     * If the metadata write fails — including when the server-side Function rejects it for
     * breaching a quota — the just-uploaded Storage file is deleted, so a rejected upload can
     * never leave an orphan consuming the user's allowance.
     */
    override suspend fun upload(request: UploadRequest): DataResult<RemoteAudioClip> =
        withContext(ioDispatcher) {
            runCatchingResult {
                ensureConfigured()

                val uploaded = storage.createFile(
                    bucketId = AppwriteConfig.bucketId,
                    fileId = ID.unique(),
                    file = InputFile.fromBytes(
                        bytes = request.bytes,
                        filename = request.fileName,
                        mimeType = mimeTypeFor(request.fileName),
                    ),
                    permissions = listOf(
                        Permission.read(Role.any()),
                        Permission.update(Role.user(request.uploaderId)),
                        Permission.delete(Role.user(request.uploaderId)),
                    ),
                )

                try {
                    val document = databases.createDocument(
                        databaseId = AppwriteConfig.databaseId,
                        collectionId = AppwriteConfig.audioMetadataCollectionId,
                        documentId = ID.unique(),
                        data = mapOf(
                            AppwriteConfig.Metadata.FILE_ID to uploaded.id,
                            AppwriteConfig.Metadata.FILE_NAME to request.fileName,
                            AppwriteConfig.Metadata.UPLOADER_ID to request.uploaderId,
                            AppwriteConfig.Metadata.UPLOADER_NAME to request.uploaderName,
                            AppwriteConfig.Metadata.FILE_SIZE_BYTES to request.sizeBytes,
                            AppwriteConfig.Metadata.LIKE_COUNT to 0,
                            AppwriteConfig.Metadata.DISLIKE_COUNT to 0,
                            // No createdAt here: Appwrite stamps every document with a built-in
                            // $createdAt, which toRemoteAudioClip() reads instead.
                        ),
                        permissions = listOf(
                            Permission.read(Role.any()),
                            Permission.delete(Role.user(request.uploaderId)),
                        ),
                    )

                    document.toRemoteAudioClip()
                        ?: throw AppErrorException(AppError.UploadRejected)
                } catch (e: Throwable) {
                    // Best-effort cleanup: if this fails too, the orphan is the server's problem,
                    // and the original failure is the one worth reporting.
                    runCatching { storage.deleteFile(AppwriteConfig.bucketId, uploaded.id) }
                    throw e
                }
            }
        }

    override suspend fun deleteClip(documentId: String, fileId: String): DataResult<Unit> =
        withContext(ioDispatcher) {
            runCatchingResult {
                ensureConfigured()
                // Metadata first: an orphaned Storage file is invisible, whereas an orphaned
                // document shows the user a clip that can never play.
                databases.deleteDocument(
                    databaseId = AppwriteConfig.databaseId,
                    collectionId = AppwriteConfig.audioMetadataCollectionId,
                    documentId = documentId,
                )
                // The Storage file is the user's data too — a failure here (missing per-file
                // delete permission, transient network) must surface, not be swallowed, or the
                // bucket silently fills with orphans that still count against the user's quota.
                // The metadata document is already gone, so the caller still removes the clip
                // from the UI; the reported failure just makes the leftover file visible.
                deleteStorageFile(fileId)
                Unit
            }
        }

    private suspend fun deleteStorageFile(fileId: String) {
        try {
            storage.deleteFile(AppwriteConfig.bucketId, fileId)
        } catch (e: AppwriteException) {
            // A 404 means the file is already gone — that is the outcome we wanted, so treat it
            // as success. Anything else (401/permissions, 5xx, network) is a real failure.
            if (e.code != 404) throw e
        }
    }

    override suspend fun usedStorageBytes(userId: String): DataResult<Long> =
        withContext(ioDispatcher) {
            runCatchingResult {
                ensureConfigured()
                var total = 0L
                var offset = 0

                while (true) {
                    val page = databases.listDocuments(
                        databaseId = AppwriteConfig.databaseId,
                        collectionId = AppwriteConfig.audioMetadataCollectionId,
                        queries = listOf(
                            Query.equal(AppwriteConfig.Metadata.UPLOADER_ID, userId),
                            // Only the size column is needed, so the rest is not transferred.
                            Query.select(listOf(AppwriteConfig.Metadata.FILE_SIZE_BYTES)),
                            Query.limit(PAGE_SIZE),
                            Query.offset(offset),
                        ),
                    )

                    total += page.documents.sumOf { document ->
                        (document.data[AppwriteConfig.Metadata.FILE_SIZE_BYTES] as? Number)
                            ?.toLong() ?: 0L
                    }

                    offset += PAGE_SIZE
                    if (page.documents.size < PAGE_SIZE || offset >= MAX_CATALOGUE_SIZE) break
                }

                total
            }
        }

    override suspend fun reactionsOf(userId: String): DataResult<Map<String, ReactionType>> =
        withContext(ioDispatcher) {
            runCatchingResult {
                if (!AppwriteConfig.isReactionsConfigured) return@runCatchingResult emptyMap()

                val reactions = mutableMapOf<String, ReactionType>()
                var offset = 0

                while (true) {
                    val page = databases.listDocuments(
                        databaseId = AppwriteConfig.databaseId,
                        collectionId = AppwriteConfig.audioReactionsCollectionId,
                        queries = listOf(
                            Query.equal(AppwriteConfig.Reactions.USER_ID, userId),
                            Query.limit(PAGE_SIZE),
                            Query.offset(offset),
                        ),
                    )

                    page.documents.forEach { document ->
                        val audioId = document.data[AppwriteConfig.Reactions.AUDIO_ID] as? String
                            ?: return@forEach
                        val type = ReactionType.fromWire(
                            document.data[AppwriteConfig.Reactions.TYPE] as? String
                        )
                        if (type != ReactionType.None) reactions[audioId] = type
                    }

                    offset += PAGE_SIZE
                    if (page.documents.size < PAGE_SIZE || offset >= MAX_CATALOGUE_SIZE) break
                }

                reactions
            }
        }

    /**
     * Applies a reaction change as delete-then-create.
     *
     * Removing the existing document first is what makes like and dislike mutually exclusive
     * without needing a transaction: there is never a moment where two documents for the same
     * (audio, user) pair both exist.
     */
    override suspend fun setReaction(
        audioId: String,
        userId: String,
        target: ReactionType,
    ): DataResult<Unit> = withContext(ioDispatcher) {
        runCatchingResult {
            if (!AppwriteConfig.isReactionsConfigured) {
                throw AppErrorException(AppError.RemoteNotConfigured)
            }

            val existing = databases.listDocuments(
                databaseId = AppwriteConfig.databaseId,
                collectionId = AppwriteConfig.audioReactionsCollectionId,
                queries = listOf(
                    Query.equal(AppwriteConfig.Reactions.AUDIO_ID, audioId),
                    Query.equal(AppwriteConfig.Reactions.USER_ID, userId),
                    Query.limit(PAGE_SIZE),
                ),
            ).documents

            // Normally at most one, but a lost race could leave duplicates; clearing them all
            // keeps the unique-pair invariant self-healing.
            existing.forEach { document ->
                databases.deleteDocument(
                    databaseId = AppwriteConfig.databaseId,
                    collectionId = AppwriteConfig.audioReactionsCollectionId,
                    documentId = document.id,
                )
            }

            val wireValue = target.wireValue ?: return@runCatchingResult

            databases.createDocument(
                databaseId = AppwriteConfig.databaseId,
                collectionId = AppwriteConfig.audioReactionsCollectionId,
                documentId = ID.unique(),
                data = mapOf(
                    AppwriteConfig.Reactions.AUDIO_ID to audioId,
                    AppwriteConfig.Reactions.USER_ID to userId,
                    AppwriteConfig.Reactions.TYPE to wireValue,
                ),
                permissions = listOf(
                    Permission.read(Role.users()),
                    Permission.update(Role.user(userId)),
                    Permission.delete(Role.user(userId)),
                ),
            )
            Unit
        }
    }

    override suspend fun counts(documentId: String): DataResult<Pair<Int, Int>> =
        withContext(ioDispatcher) {
            runCatchingResult {
                ensureConfigured()
                val document = databases.getDocument(
                    databaseId = AppwriteConfig.databaseId,
                    collectionId = AppwriteConfig.audioMetadataCollectionId,
                    documentId = documentId,
                )
                val likes =
                    (document.data[AppwriteConfig.Metadata.LIKE_COUNT] as? Number)?.toInt() ?: 0
                val dislikes =
                    (document.data[AppwriteConfig.Metadata.DISLIKE_COUNT] as? Number)?.toInt() ?: 0
                likes to dislikes
            }
        }

    private fun ensureConfigured() {
        if (!isConfigured) throw AppErrorException(AppError.RemoteNotConfigured)
    }

    private companion object {
        const val PAGE_SIZE = 100

        /** A safety stop, so a runaway pagination loop cannot spin forever. */
        const val MAX_CATALOGUE_SIZE = 5_000

        fun mimeTypeFor(fileName: String): String =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "mp3" -> "audio/mpeg"
                "m4a", "mp4", "aac" -> "audio/mp4"
                "ogg", "oga" -> "audio/ogg"
                "wav" -> "audio/wav"
                "opus" -> "audio/opus"
                "flac" -> "audio/flac"
                else -> "audio/mpeg"
            }
    }
}

/**
 * Maps a raw document, returning null when required attributes are missing.
 *
 * A single malformed row should drop out of the catalogue, not fail the whole listing.
 */
private fun io.appwrite.models.Document<Map<String, Any>>.toRemoteAudioClip(): RemoteAudioClip? {
    val fileId = data[AppwriteConfig.Metadata.FILE_ID] as? String ?: return null
    if (fileId.isBlank()) return null

    return RemoteAudioClip(
        documentId = id,
        fileId = fileId,
        fileName = (data[AppwriteConfig.Metadata.FILE_NAME] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: "clip.mp3",
        uploaderId = data[AppwriteConfig.Metadata.UPLOADER_ID] as? String ?: "",
        uploaderName = (data[AppwriteConfig.Metadata.UPLOADER_NAME] as? String)
            ?.takeIf { it.isNotBlank() }
            ?: "Someone",
        sizeBytes = (data[AppwriteConfig.Metadata.FILE_SIZE_BYTES] as? Number)?.toLong() ?: 0L,
        // `createdAt` here is the Document's own built-in $createdAt (io.appwrite.models
        // .Document.createdAt), not a custom attribute — Appwrite stamps every document with
        // one automatically, so there is nothing for the app to write or maintain.
        createdAt = parseTimestamp(createdAt) ?: 0L,
        likeCount = (data[AppwriteConfig.Metadata.LIKE_COUNT] as? Number)?.toInt() ?: 0,
        dislikeCount = (data[AppwriteConfig.Metadata.DISLIKE_COUNT] as? Number)?.toInt() ?: 0,
    )
}

/** Appwrite datetimes are ISO-8601; anything unparseable is treated as unknown rather than 1970. */
private fun parseTimestamp(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}
