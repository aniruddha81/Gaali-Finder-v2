package com.aniruddha81.gaalifinderv2.appwrite

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aniruddha81.gaalifinderv2.Constants
import com.aniruddha81.gaalifinderv2.models.AudioFile
import com.aniruddha81.gaalifinderv2.data.AudioFileDao
import com.aniruddha81.gaalifinderv2.data.FileStorageManagerForIPS
import io.appwrite.Client
import io.appwrite.services.Account
import io.appwrite.services.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.inject.Inject

class AppwriteRepository @Inject constructor(context: Context, private val dao: AudioFileDao) {

    private val client = Client(context)
        .setEndpoint(Constants.APPWRITE_ENDPOINT)
        .setProject(Constants.APPWRITE_PROJECT_ID)
        .setSelfSigned(true)

    private val storage = Storage(client)
    private val account = Account(client)

    init {
        CoroutineScope(Dispatchers.IO).launch {
            loginAnonymously()
        }
    }

    private suspend fun loginAnonymously() {
        try {
            account.createAnonymousSession()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun fetchAudioFiles(context: Context) = withContext(Dispatchers.IO) {
        if (!isInternetAvailable(context)) return@withContext

        val response = storage.listFiles(Constants.APPWRITE_BUCKET_ID)

        response.files.forEach { file ->
//            if (dao.isFileStored(file.name) == 0) {
            if (dao.isFileStoredByUniqueFileIdOfAppwrite(file.id) == 0) {
                val downloadedFile = storage.getFileDownload(Constants.APPWRITE_BUCKET_ID, file.id)
                val filePath = FileStorageManagerForIPS.saveAudioFileToIPS(
                    context, file.name,
                    ByteArrayInputStream(downloadedFile)
                )

                dao.insertAudioFile(
                    AudioFile(
                        fileName = file.name,
                        path = filePath,
                        source = file.id
                    )
                )
            }
        }
    }

    private fun isInternetAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}