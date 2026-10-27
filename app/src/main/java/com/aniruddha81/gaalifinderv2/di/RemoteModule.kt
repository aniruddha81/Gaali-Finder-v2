package com.aniruddha81.gaalifinderv2.di

import com.aniruddha81.gaalifinderv2.data.remote.ApiClient
import com.aniruddha81.gaalifinderv2.data.remote.ApiConfig
import com.aniruddha81.gaalifinderv2.data.remote.TokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RemoteModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The address comes from `BuildConfig`, so it is never committed as a source constant. */
    @Provides
    @Singleton
    fun provideApiClient(http: OkHttpClient, tokens: TokenStore): ApiClient =
        ApiClient(http, tokens, ApiConfig.baseUrl)
}
