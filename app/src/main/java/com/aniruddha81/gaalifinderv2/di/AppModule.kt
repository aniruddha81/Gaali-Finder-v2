package com.aniruddha81.gaalifinderv2.di

import android.content.Context
import androidx.room.Room
import com.aniruddha81.gaalifinderv2.appwrite.AppwriteRepository
import com.aniruddha81.gaalifinderv2.data.AudioDatabase
import com.aniruddha81.gaalifinderv2.data.AudioFileDao
import com.aniruddha81.gaalifinderv2.data.AudioRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun provideContext(@ApplicationContext context: Context): Context = context

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AudioDatabase {
        return Room.databaseBuilder(
            context, AudioDatabase::class.java,
            "audio_database"
        ).build()
    }

    @Provides
    fun provideAudioDao(database: AudioDatabase) : AudioFileDao = database.audioDao()

    @Provides
    @Singleton
    fun provideAudioRepository(dao: AudioFileDao): AudioRepository {
        return AudioRepository(dao)
    }

    @Provides
    @Singleton
    fun provideAppwriteRepository(@ApplicationContext context: Context, dao: AudioFileDao): AppwriteRepository {
        return AppwriteRepository(context, dao)
    }
}