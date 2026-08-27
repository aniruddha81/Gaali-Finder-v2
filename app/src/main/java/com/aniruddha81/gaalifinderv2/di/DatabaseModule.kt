package com.aniruddha81.gaalifinderv2.di

import android.content.Context
import androidx.room.Room
import com.aniruddha81.gaalifinderv2.data.local.ALL_MIGRATIONS
import com.aniruddha81.gaalifinderv2.data.local.AudioDatabase
import com.aniruddha81.gaalifinderv2.data.local.dao.AudioFileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AudioDatabase =
        Room.databaseBuilder(context, AudioDatabase::class.java, AudioDatabase.NAME)
            .addMigrations(*ALL_MIGRATIONS)
            // A user's imported clips are irreplaceable, so a missing migration must fail loudly
            // in development rather than silently wiping the library on a released build.
            .build()

    @Provides
    fun provideAudioDao(database: AudioDatabase): AudioFileDao = database.audioDao()
}
