package com.aniruddha81.gaalifinderv2.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aniruddha81.gaalifinderv2.models.AudioFile
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioFileDao {
//    @Query("SELECT * FROM audio_files ORDER BY LOWER(fileName) ASC")
//    fun getAllAudioFiles(): Flow<List<AudioFile>>

    @Query("SELECT * FROM audio_files")
    fun getAllAudioFiles(): Flow<List<AudioFile>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAudioFile(audioFile: AudioFile)

    @Delete
    suspend fun deleteAudioFile(audioFile: AudioFile)

    @Query("SELECT COUNT(*) FROM audio_files WHERE fileName = :fileName")
    suspend fun isFileStored(fileName: String): Int

    @Query("UPDATE audio_files SET fileName = :newName WHERE id = :audioId")
    suspend fun renameAudioFile(audioId: Long, newName: String)
}