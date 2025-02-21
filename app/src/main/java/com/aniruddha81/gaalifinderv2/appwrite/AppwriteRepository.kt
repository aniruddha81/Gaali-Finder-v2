package com.aniruddha81.gaalifinderv2.appwrite

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aniruddha81.gaalifinderv2.Constants
import com.aniruddha81.gaalifinderv2.data.AudioFile
import com.aniruddha81.gaalifinderv2.data.AudioFileDao
import com.aniruddha81.gaalifinderv2.data.FileStorageManager
import io.appwrite.Client
import io.appwrite.services.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.inject.Inject

class AppwriteRepository @Inject constructor(context: Context, private val dao: AudioFileDao) {

    private val client = Client(context)
        .setEndpoint("https://cloud.appwrite.io/v1")
        .setProject(Constants.APPWRITE_PROJECT_ID)

    private val storage = Storage(client)

    suspend fun fetchAudioFiles(context: Context) = withContext(Dispatchers.IO) {
        if (!isInternetAvailable(context)) return@withContext

        val response = storage.listFiles(Constants.APPWRITE_BUCKET_ID)

        response.files.forEach { file ->
            if (dao.isFileStored(file.name) == 0) {
                val downloadedFile = storage.getFileDownload(Constants.APPWRITE_BUCKET_ID, file.id)
                val filePath = FileStorageManager.saveAudioFile(
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