package com.aniruddha81.gaalifinderv2.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aniruddha81.gaalifinderv2.models.AudioFile


// this class is only used for creating object of DAO in the hilt module

@Database(entities = [AudioFile::class], version = 1, exportSchema = false)
abstract class AudioDatabase : RoomDatabase() {

    abstract fun audioDao(): AudioFileDao
}