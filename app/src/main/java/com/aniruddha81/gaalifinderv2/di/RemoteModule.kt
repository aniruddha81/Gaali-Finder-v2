package com.aniruddha81.gaalifinderv2.di

import android.content.Context
import com.aniruddha81.gaalifinderv2.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.appwrite.Client
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RemoteModule {

    /**
     * Credentials come from `BuildConfig`, which reads `local.properties` or the environment at
     * build time — they are no longer committed as source constants.
     */
    @Provides
    @Singleton
    fun provideAppwriteClient(@ApplicationContext context: Context): Client =
        Client(context)
            .setEndpoint(BuildConfig.APPWRITE_ENDPOINT)
            .setProject(BuildConfig.APPWRITE_PROJECT_ID)
            .setSelfSigned(BuildConfig.DEBUG)
}
