package com.aniruddha81.gaalifinderv2.appwrite

import android.content.Context
import com.aniruddha81.gaalifinderv2.appwrite.NetworkUtils.isInternetAvailable
import com.aniruddha81.gaalifinderv2.data.AudioDatabase
import com.aniruddha81.gaalifinderv2.data.AudioFileDao
import com.aniruddha81.gaalifinderv2.data.FileStorageManager
import io.appwrite.Client
import io.appwrite.services.Account
import io.appwrite.services.Storage

class AppwriteRepository(val context: Context, val dao: AudioFileDao) {
    private val client = Client(context)
        .setEndpoint("YOUR_APPWRITE_ENDPOINT")
        .setProject("YOUR_PROJECT_ID")

    private val storage = Storage(client)
    private val account = Account(client)

    init {
        loginAnonymously()
    }
    private fun loginAnonymously() {
        account.createAnonymousSession()
    }

    suspend fun fetchAudioFiles(context: Context) {
        if (!isInternetAvailable(context)) return

        val storedFiles = dao.getStoredFilenames().toSet()
        val response = storage.listFiles("YOUR_BUCKET_ID")

        for (file in response.files) {
            if (!storedFiles.contains(file.name)) {
                val downloadedFile = storage.getFileDownload("YOUR_BUCKET_ID", file.id)
                val filePath = FileStorageManager.saveAudioFile(context, file.name, downloadedFile.)

                val audioFile = AudioFile(fileName = file.name, path = filePath, source = file.$id)
                dao.insertFile(audioFile)
            }
        }
    }
}