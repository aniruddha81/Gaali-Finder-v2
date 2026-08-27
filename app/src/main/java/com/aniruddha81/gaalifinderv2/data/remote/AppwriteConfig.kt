package com.aniruddha81.gaalifinderv2.data.remote

import com.aniruddha81.gaalifinderv2.BuildConfig

/**
 * The Appwrite resource ids this build talks to.
 *
 * Values come from `BuildConfig`, which reads `local.properties` or the environment at build
 * time, so nothing here is committed. [isConfigured] gates every remote call: a build without
 * credentials degrades to "catalogue unavailable" instead of throwing on every screen.
 */
object AppwriteConfig {

    val endpoint: String get() = BuildConfig.APPWRITE_ENDPOINT
    val projectId: String get() = BuildConfig.APPWRITE_PROJECT_ID
    val bucketId: String get() = BuildConfig.APPWRITE_BUCKET_ID
    val databaseId: String get() = BuildConfig.APPWRITE_DATABASE_ID

    val audioMetadataCollectionId: String get() = BuildConfig.APPWRITE_AUDIO_METADATA_COLLECTION_ID
    val audioReactionsCollectionId: String get() = BuildConfig.APPWRITE_AUDIO_REACTIONS_COLLECTION_ID
    val userProfilesCollectionId: String get() = BuildConfig.APPWRITE_USER_PROFILES_COLLECTION_ID

    /**
     * The scheme Appwrite's SDK returns the OAuth result on. The app manifest must register an
     * activity for exactly this value, or the browser has nowhere to hand the session back to.
     */
    val oauthCallbackScheme: String get() = "appwrite-callback-$projectId"

    /** True when this build has enough credentials to reach the catalogue at all. */
    val isConfigured: Boolean
        get() = projectId.isNotBlank() &&
            bucketId.isNotBlank() &&
            databaseId.isNotBlank() &&
            audioMetadataCollectionId.isNotBlank()

    /** Reactions need their own collection; the feed still works without one. */
    val isReactionsConfigured: Boolean
        get() = isConfigured && audioReactionsCollectionId.isNotBlank()

    /** Attribute names, kept in one place so a rename in the Console is a one-line change here. */
    object Metadata {
        const val FILE_ID = "fileId"
        const val UPLOADER_ID = "uploaderId"
        const val UPLOADER_NAME = "uploaderName"
        const val FILE_NAME = "fileName"
        const val FILE_SIZE_BYTES = "fileSizeBytes"
        // No custom createdAt: Appwrite stamps every document with a built-in $createdAt
        // (exposed by the SDK's Document model as `createdAt`), so there is nothing to declare
        // or write for it here.
        const val LIKE_COUNT = "likeCount"
        const val DISLIKE_COUNT = "dislikeCount"
    }

    object Reactions {
        const val AUDIO_ID = "audioId"
        const val USER_ID = "userId"
        const val TYPE = "type"
    }

    object Profiles {
        const val USER_ID = "userId"
        const val IS_PREMIUM = "isPremium"
        const val PREMIUM_STORAGE_LIMIT_BYTES = "premiumStorageLimitBytes"
        // No custom updatedAt: this collection is admin/Function-write-only, and Appwrite's
        // built-in $updatedAt already tracks when a document was last changed.
    }
}
