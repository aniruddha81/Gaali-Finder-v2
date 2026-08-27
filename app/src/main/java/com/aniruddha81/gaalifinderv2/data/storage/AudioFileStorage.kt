package com.aniruddha81.gaalifinderv2.data.storage

import android.content.Context
import com.aniruddha81.gaalifinderv2.core.dispatcher.IoDispatcher
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.error.AppErrorException
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.core.result.runCatchingResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A local cache of clips that have been played.
 *
 * This is no longer a library the user owns — Appwrite is the only source of clips. The cache
 * exists because [android.media.MediaPlayer] needs a path or URI to read from, and because
 * re-downloading the same clip on every tap would be both slow and wasteful. Anything in here
 * can be deleted at any time and will simply be fetched again.
 */
interface AudioFileStorage {
    /** Writes [bytes] under a name derived from [fileId], returning where it landed. */
    suspend fun cache(fileId: String, fileName: String, bytes: ByteArray): DataResult<String>

    /** The cached copy for this file id, or null when it has not been downloaded yet. */
    fun cachedPathFor(fileId: String, fileName: String): String?

    suspend fun delete(path: String): DataResult<Unit>

    /** Removes every cached clip, e.g. when the catalogue is reset. */
    suspend fun clear(): DataResult<Unit>

    fun exists(path: String): Boolean
}

@Singleton
class InternalAudioFileStorage @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AudioFileStorage {

    private val clipsDir: File
        get() = File(context.filesDir, CLIPS_DIRECTORY).apply { if (!exists()) mkdirs() }

    override suspend fun cache(
        fileId: String,
        fileName: String,
        bytes: ByteArray,
    ): DataResult<String> = withContext(ioDispatcher) {
        runCatchingResult {
            if (bytes.isEmpty()) throw AppErrorException(AppError.Storage())

            val target = fileFor(fileId, fileName)
            // Write to a temp file first so a failure part-way through cannot leave a truncated
            // clip sitting at the real path, looking valid to the player.
            val temp = File(target.parentFile, "${target.name}.$TEMP_SUFFIX")
            try {
                temp.outputStream().use { it.write(bytes) }
                if (temp.length() == 0L) throw AppErrorException(AppError.Storage())
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target.absolutePath
            } catch (e: Throwable) {
                temp.delete()
                throw e
            }
        }
    }

    override fun cachedPathFor(fileId: String, fileName: String): String? =
        runCatching { fileFor(fileId, fileName).takeIf { it.length() > 0 }?.absolutePath }
            .getOrNull()

    override suspend fun delete(path: String): DataResult<Unit> = withContext(ioDispatcher) {
        runCatchingResult {
            val file = File(path)
            // A file that is already gone is the state the caller wanted, not a failure.
            if (file.exists() && !file.delete()) throw AppErrorException(AppError.Storage())
        }
    }

    override suspend fun clear(): DataResult<Unit> = withContext(ioDispatcher) {
        runCatchingResult {
            clipsDir.listFiles()?.forEach { runCatching { it.delete() } }
            Unit
        }
    }

    override fun exists(path: String): Boolean =
        runCatching { File(path).length() > 0 }.getOrDefault(false)

    /**
     * Names the cached file after the Storage file id, keeping the original extension so
     * `MediaPlayer` can still sniff the format.
     *
     * The id rather than the display name means two clips called `oi.mp3` from different
     * uploaders cannot collide in the cache.
     */
    private fun fileFor(fileId: String, fileName: String): File {
        val extension = fileName.substringAfterLast('.', "")
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifBlank { DEFAULT_EXTENSION }
        val safeId = fileId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .ifBlank { "clip" }
        return File(clipsDir, "$safeId.$extension")
    }

    private companion object {
        const val CLIPS_DIRECTORY = "clips"
        const val TEMP_SUFFIX = "part"
        const val DEFAULT_EXTENSION = "mp3"
    }
}
