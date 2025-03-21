package com.aniruddha81.gaalifinderv2.data

import com.aniruddha81.gaalifinderv2.models.AudioFile
import javax.inject.Inject

class AudioRepository @Inject constructor(private val dao: AudioFileDao) {
    fun getAudioFiles() = dao.getAllAudioFiles()

    //    adds audioFile in roomDB
    suspend fun addAudioFile(audio: AudioFile) {
        dao.insertAudioFile(audio)
    }

    //    deletes from roomDB
    suspend fun deleteAudioFile(audio: AudioFile) {
        dao.deleteAudioFile(audio)
    }

    suspend fun updateAudioFile(audioFile: AudioFile) {
        dao.updateAudioFile(audioFile)
    }

    suspend fun getAudioFileById(id: Long): AudioFile {
        return dao.getAudioFileById(id)
    }
}