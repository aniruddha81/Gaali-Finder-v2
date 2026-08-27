package com.aniruddha81.gaalifinderv2.data.storage

import android.media.MediaMetadataRetriever
import com.aniruddha81.gaalifinderv2.core.dispatcher.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the playback duration of a stored clip so the UI can show it without playing it. */
interface AudioMetadataReader {
    /** Returns the duration in milliseconds, or 0 when it cannot be determined. */
    suspend fun readDurationMs(path: String): Long
}

@Singleton
class MediaMetadataAudioReader @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AudioMetadataReader {

    override suspend fun readDurationMs(path: String): Long = withContext(ioDispatcher) {
        if (!File(path).exists()) return@withContext 0L

        // A duration we cannot read is cosmetic — the clip still plays — so every failure here
        // degrades to 0 rather than propagating and blocking an import.
        runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(path)
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
            }
        }.getOrDefault(0L)
    }
}

/**
 * [MediaMetadataRetriever] only became [AutoCloseable] on API 29; this keeps the `use` block
 * working down to the app's minSdk of 26.
 */
private inline fun <R> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> R): R = try {
    block(this)
} finally {
    runCatching { release() }
}
