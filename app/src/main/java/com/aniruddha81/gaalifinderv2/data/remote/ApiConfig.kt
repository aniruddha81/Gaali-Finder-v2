package com.aniruddha81.gaalifinderv2.data.remote

import com.aniruddha81.gaalifinderv2.BuildConfig

/**
 * Where the Gaali API lives and which Google client signs people in.
 *
 * Values come from `BuildConfig`, which reads `local.properties` or the environment at build
 * time, so nothing here is committed. A build without an address degrades to "catalogue
 * unavailable" instead of throwing on every screen.
 */
object ApiConfig {
    val baseUrl: String get() = BuildConfig.API_BASE_URL.trim().trimEnd('/')
    val googleWebClientId: String get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()

    val isConfigured: Boolean get() = baseUrl.isNotEmpty()
}
