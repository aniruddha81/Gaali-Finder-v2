package com.aniruddha81.gaalifinderv2.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object FileStorageManager {

    //    saves file to internal private storage
    fun saveAudioFile(context: Context, fileName: String, inputStream: InputStream): String {
        val file = File(context.filesDir, fileName)
        inputStream.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file.absolutePath
    }

    //    deletes from stored-path (i.p.s)
    fun deleteAudioFile(filePath: String) {
        val file = File(filePath)
        if (file.exists()) file.delete()
    }
}