package com.aniruddha81.gaalifinderv2.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aniruddha81.gaalifinderv2.data.local.dao.AudioFileDao
import com.aniruddha81.gaalifinderv2.data.local.entity.AudioFileEntity

@Database(
    entities = [AudioFileEntity::class],
    version = AudioDatabase.VERSION,
    exportSchema = true,
)
abstract class AudioDatabase : RoomDatabase() {

    abstract fun audioDao(): AudioFileDao

    companion object {
        const val VERSION = 3
        const val NAME = "audio_database"
    }
}
