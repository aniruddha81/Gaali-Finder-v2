package com.aniruddha81.gaalifinderv2.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AudioFileDao {
    @Query("SELECT * FROM audio_files")
    fun getAllAudioFiles(): LiveData<List<AudioFile>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAudioFile(audioFile: AudioFile)

    @Delete
    suspend fun deleteAudioFile(audioFile: AudioFile)

    @Query("SELECT fileName FROM audio_files")
    suspend fun getStoredFilenames(): List<String>
}