package com.aniruddha81.gaalifinderv2.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aniruddha81.gaalifinderv2.model.AudioFile

@Dao
interface AudioFileDao {
    @Query("SELECT * FROM audiofile")
    fun getAllAudio(): LiveData<List<AudioFile>>

    @Insert
    fun addAudio(audioFile : AudioFile)
}