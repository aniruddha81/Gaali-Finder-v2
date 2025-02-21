package com.aniruddha81.gaalifinderv2.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [AudioFile::class], version = 1, exportSchema = false)
abstract class AudioDatabase : RoomDatabase() {

    abstract fun audioDao(): AudioFileDao
}