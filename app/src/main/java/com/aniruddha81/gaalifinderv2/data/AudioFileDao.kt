package com.aniruddha81.gaalifinderv2.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioFileDao {
    @Query("SELECT * FROM audio_files")
    fun getAllAudioFiles(): Flow<List<AudioFile>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAudioFile(audioFile: AudioFile)

    @Delete
    suspend fun deleteAudioFile(audioFile: AudioFile)

    @Query("SELECT COUNT(*) FROM audio_files WHERE fileName = :fileName")
    suspend fun isFileStored(fileName: String): Int
}