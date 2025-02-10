package com.aniruddha81.gaalifinderv2.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aniruddha81.gaalifinderv2.dao.AudioFileDao
import com.aniruddha81.gaalifinderv2.model.AudioFile
import com.aniruddha81.gaalifinderv2.model.Converters

@Database(entities = [AudioFile::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AudioDatabase : RoomDatabase() {
    companion object {
        const val NAME = "audio_files"
    }

    abstract fun getAudioFileDao(): AudioFileDao
}