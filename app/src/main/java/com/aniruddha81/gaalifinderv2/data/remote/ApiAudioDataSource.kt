package com.aniruddha81.gaalifinderv2.data.remote

import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.error.AppErrorException
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.core.result.runCatchingResult
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiAudioDataSource @Inject constructor(
    private val api: ApiClient,
) : RemoteAudioDataSource {

    override val isConfigured: Boolean get() = api.isConfigured

    override suspend fun listClips(): DataResult<List<RemoteAudioClip>> = runCatchingResult {
        ensureConfigured()
        val collected = mutableListOf<RemoteAudioClip>()
        var cursor = ""
        do {
            val query = "?limit=100" + if (cursor.isEmpty()) "" else "&cursor=" + URLEncoder.encode(cursor, "UTF-8")
            val page = api.json("GET", "/v1/clips$query", auth = false)
            val clips = page.getJSONArray("clips")
            // A malformed row must not abort the whole listing.
            for (i in 0 until clips.length()) clips.getJSONObject(i).toRemoteClip()?.let(collected::add)
            cursor = page.optString("nextCursor")
        } while (cursor.isNotEmpty() && collected.size < MAX_CATALOGUE_SIZE)
        collected
    }

    override suspend fun downloadFile(fileId: String): DataResult<ByteArray> = runCatchingResult {
        ensureConfigured()
        api.bytes("/files/" + URLEncoder.encode(fileId, "UTF-8"))
    }

    override suspend fun upload(request: UploadRequest): DataResult<RemoteAudioClip> =
        runCatchingResult {
            ensureConfigured()
            // The uploader id and name are not sent: the server takes them from the session.
            api.upload("/v1/clips", request.fileName, request.bytes, mimeTypeFor(request.fileName))
                .toRemoteClip()
                ?: throw AppErrorException(AppError.UploadRejected)
        }

    override suspend fun deleteClip(documentId: String, fileId: String): DataResult<Unit> =
        runCatchingResult {
            ensureConfigured()
            try {
                api.json("DELETE", "/v1/clips/$documentId")
            } catch (e: AppErrorException) {
                // The API answers 404 for a clip that is already gone — the outcome we wanted.
                if (e.error !== AppError.ClipFileMissing) throw e
            }
            Unit
        }

    override suspend fun usedStorageBytes(userId: String): DataResult<Long> = runCatchingResult {
        ensureConfigured()
        api.json("GET", "/v1/me").getLong("usedBytes")
    }

    override suspend fun reactionsOf(userId: String): DataResult<Map<String, ReactionType>> =
        runCatchingResult {
            ensureConfigured()
            val json = api.json("GET", "/v1/me/reactions")
            buildMap {
                for (clipId in json.keys()) {
                    val type = ReactionType.fromWire(json.optString(clipId))
                    if (type != ReactionType.None) put(clipId, type)
                }
            }
        }

    override suspend fun setReaction(
        audioId: String,
        userId: String,
        target: ReactionType,
    ): DataResult<Pair<Int, Int>> = runCatchingResult {
        ensureConfigured()
        val counts = api.json(
            "PUT",
            "/v1/clips/$audioId/reaction",
            JSONObject().put("kind", target.wireValue ?: "none"),
        )
        counts.getInt("likeCount") to counts.getInt("dislikeCount")
    }

    private fun ensureConfigured() {
        if (!isConfigured) throw AppErrorException(AppError.RemoteNotConfigured)
    }

    private companion object {
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

private fun JSONObject.toRemoteClip(): RemoteAudioClip? {
    val fileId = optString("fileId")
    if (fileId.isBlank()) return null
    return RemoteAudioClip(
        documentId = optString("id"),
        fileId = fileId,
        fileName = optString("fileName").ifBlank { "clip.mp3" },
        uploaderId = optString("uploaderId"),
        uploaderName = optString("uploaderName").ifBlank { "Someone" },
        sizeBytes = optLong("sizeBytes"),
        createdAt = optLong("createdAt"),
        likeCount = optInt("likeCount"),
        dislikeCount = optInt("dislikeCount"),
    )
}
