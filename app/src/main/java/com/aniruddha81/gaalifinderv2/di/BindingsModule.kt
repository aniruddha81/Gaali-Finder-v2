package com.aniruddha81.gaalifinderv2.di

import com.aniruddha81.gaalifinderv2.core.connectivity.AndroidConnectivityMonitor
import com.aniruddha81.gaalifinderv2.core.connectivity.ConnectivityMonitor
import com.aniruddha81.gaalifinderv2.core.media.AudioPlayer
import com.aniruddha81.gaalifinderv2.core.media.AudioPlayerController
import com.aniruddha81.gaalifinderv2.data.remote.AppwriteAudioDataSource
import com.aniruddha81.gaalifinderv2.data.remote.RemoteAudioDataSource
import com.aniruddha81.gaalifinderv2.data.repository.AudioClipRepositoryImpl
import com.aniruddha81.gaalifinderv2.data.storage.AudioFileStorage
import com.aniruddha81.gaalifinderv2.data.storage.AudioMetadataReader
import com.aniruddha81.gaalifinderv2.data.storage.InternalAudioFileStorage
import com.aniruddha81.gaalifinderv2.data.storage.MediaMetadataAudioReader
import com.aniruddha81.gaalifinderv2.domain.repository.AudioClipRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds each interface to its production implementation.
 *
 * Everything upstream depends on the interface, so a test can substitute a fake without
 * touching Room, the filesystem, or the network.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    @Singleton
    abstract fun bindAudioClipRepository(impl: AudioClipRepositoryImpl): AudioClipRepository

    @Binds
    @Singleton
    abstract fun bindRemoteAudioDataSource(impl: AppwriteAudioDataSource): RemoteAudioDataSource

    @Binds
    @Singleton
    abstract fun bindAudioFileStorage(impl: InternalAudioFileStorage): AudioFileStorage

    @Binds
    @Singleton
    abstract fun bindAudioMetadataReader(impl: MediaMetadataAudioReader): AudioMetadataReader

    @Binds
    @Singleton
    abstract fun bindConnectivityMonitor(impl: AndroidConnectivityMonitor): ConnectivityMonitor

    @Binds
    @Singleton
    abstract fun bindAudioPlayer(impl: AudioPlayerController): AudioPlayer
}
