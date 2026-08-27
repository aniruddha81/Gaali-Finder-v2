package com.aniruddha81.gaalifinderv2.data.remote

import com.aniruddha81.gaalifinderv2.BuildConfig
import com.aniruddha81.gaalifinderv2.core.dispatcher.IoDispatcher
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.error.AppErrorException
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.core.result.runCatchingResult
import io.appwrite.Client
import io.appwrite.services.Account
import io.appwrite.services.Storage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A clip as it exists in the shared Appwrite bucket. */
data class RemoteAudioFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
)

/** Read-only access to the shared clip catalogue. */
interface RemoteAudioDataSource {
    /** True when this build has credentials to reach the catalogue at all. */
    val isConfigured: Boolean

    suspend fun listFiles(): DataResult<List<RemoteAudioFile>>
    suspend fun downloadFile(fileId: String): DataResult<ByteArray>
}

@Singleton
class AppwriteAudioDataSource @Inject constructor(
    private val client: Client,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : RemoteAudioDataSource {

    private val storage by lazy { Storage(client) }
    private val account by lazy { Account(client) }

    private val sessionMutex = Mutex()
    @Volatile
    private var hasSession = false

    override val isConfigured: Boolean =
        BuildConfig.APPWRITE_PROJECT_ID.isNotBlank() && BuildConfig.APPWRITE_BUCKET_ID.isNotBlank()

    override suspend fun listFiles(): DataResult<List<RemoteAudioFile>> =
        withContext(ioDispatcher) {
            runCatchingResult {
                ensureConfigured()
                ensureSession()
                storage.listFiles(BuildConfig.APPWRITE_BUCKET_ID).files.map { file ->
                    RemoteAudioFile(
                        id = file.id,
                        name = file.name,
                        sizeBytes = file.sizeOriginal,
                    )
                }
            }
        }

    override suspend fun downloadFile(fileId: String): DataResult<ByteArray> =
        withContext(ioDispatcher) {
            runCatchingResult {
                ensureConfigured()
                ensureSession()
                storage.getFileDownload(BuildConfig.APPWRITE_BUCKET_ID, fileId)
            }
        }

    private fun ensureConfigured() {
        if (!isConfigured) throw AppErrorException(AppError.RemoteNotConfigured)
    }

    /**
     * The bucket is read via an anonymous session. Creating it is guarded by a mutex so that
     * concurrent syncs cannot race into creating two sessions, and it is only attempted once
     * per process — the previous version fired login from a bare `CoroutineScope` in `init`,
     * which meant the first fetch could easily beat it and fail with a 401.
     */
    private suspend fun ensureSession() {
        if (hasSession) return
        sessionMutex.withLock {
            if (hasSession) return
            runCatching { account.get() }
                .onSuccess { hasSession = true }
                .onFailure {
                    account.createAnonymousSession()
                    hasSession = true
                }
        }
    }
}
