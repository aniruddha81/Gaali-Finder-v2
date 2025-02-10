package com.aniruddha81.gaalifinderv2.db

import android.app.Application
import androidx.room.Room

class MainApplication : Application() {
    companion object {
        lateinit var audio_database: AudioDatabase
    }

    override fun onCreate() {
        super.onCreate()
        audio_database = Room.databaseBuilder(
            applicationContext, AudioDatabase::class.java,
            AudioDatabase.NAME
        ).build()
    }
}