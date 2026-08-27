package com.aniruddha81.gaalifinderv2.core.dispatcher

import javax.inject.Qualifier

/**
 * Dispatchers are injected rather than referenced statically so tests can swap in a
 * deterministic test dispatcher instead of a real thread pool.
 */
@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class IoDispatcher

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class DefaultDispatcher

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class MainDispatcher

/** Application-lifetime coroutine scope, for work that must outlive any one screen. */
@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class ApplicationScope
