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
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the on-disk copies of clips, inside the app's private storage. */
interface AudioFileStorage {
    suspend fun save(fileName: String, source: InputStream): DataResult<StoredAudioFile>
    suspend fun delete(path: String): DataResult<Unit>
    suspend fun rename(currentPath: String, newFileName: String): DataResult<StoredAudioFile>
    fun exists(path: String): Boolean
    fun resolve(fileName: String): File
}

data class StoredAudioFile(
    val path: String,
    val sizeBytes: Long,
)

@Singleton
class InternalAudioFileStorage @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AudioFileStorage {

    private val clipsDir: File
        get() = File(context.filesDir, CLIPS_DIRECTORY).apply { if (!exists()) mkdirs() }

    override suspend fun save(
        fileName: String,
        source: InputStream,
    ): DataResult<StoredAudioFile> = withContext(ioDispatcher) {
        runCatchingResult {
            val target = resolve(fileName)
            // Write to a temp file first so a failure part-way through cannot leave a
            // truncated clip sitting at the real path, looking valid to the database.
            val temp = File(target.parentFile, "${target.name}.$TEMP_SUFFIX")
            try {
                source.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                if (temp.length() == 0L) throw AppErrorException(AppError.Storage())
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                StoredAudioFile(path = target.absolutePath, sizeBytes = target.length())
            } catch (e: Throwable) {
                temp.delete()
                throw e
            }
        }
    }

    override suspend fun delete(path: String): DataResult<Unit> = withContext(ioDispatcher) {
        runCatchingResult {
            val file = File(path)
            // A file that is already gone is the state the caller wanted, not a failure.
            if (file.exists() && !file.delete()) throw AppErrorException(AppError.Storage())
        }
    }

    override suspend fun rename(
        currentPath: String,
        newFileName: String,
    ): DataResult<StoredAudioFile> = withContext(ioDispatcher) {
        runCatchingResult {
            val current = File(currentPath)
            if (!current.exists()) throw AppErrorException(AppError.ClipFileMissing)

            val target = File(current.parentFile ?: clipsDir, newFileName)
            if (target.absolutePath == current.absolutePath) {
                return@runCatchingResult StoredAudioFile(current.absolutePath, current.length())
            }
            if (target.exists()) throw AppErrorException(AppError.DuplicateName)
            if (!current.renameTo(target)) throw AppErrorException(AppError.Storage())

            StoredAudioFile(path = target.absolutePath, sizeBytes = target.length())
        }
    }

    override fun exists(path: String): Boolean =
        runCatching { File(path).exists() }.getOrDefault(false)

    /**
     * Clips saved before v3 live directly in `filesDir`; new ones go in a `clips/` subdirectory.
     * Existing files are resolved where they already are so nothing has to be moved.
     */
    override fun resolve(fileName: String): File {
        val legacy = File(context.filesDir, fileName)
        return if (legacy.exists()) legacy else File(clipsDir, fileName)
    }

    private companion object {
        const val CLIPS_DIRECTORY = "clips"
        const val TEMP_SUFFIX = "part"
    }
}
