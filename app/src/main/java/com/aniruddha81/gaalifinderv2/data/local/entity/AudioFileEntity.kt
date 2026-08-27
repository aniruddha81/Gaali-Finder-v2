package com.aniruddha81.gaalifinderv2.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached row for one clip in the shared catalogue.
 *
 * Since v4 the table mirrors the `audio_metadata` collection rather than recording files the
 * user saved locally: the primary key is the Appwrite document id, and [cachedPath] stays null
 * until the audio has actually been downloaded for playback. Keeping the mirror means the grid
 * renders instantly on launch and offline, while Appwrite stays authoritative.
 */
@Entity(
    tableName = "audio_clips",
    indices = [
        Index(value = ["fileName"]),
        Index(value = ["uploaderId"]),
        Index(value = ["createdAt"]),
    ],
)
data class AudioFileEntity(
    @PrimaryKey val documentId: String,
    val fileId: String = "",
    val fileName: String = "",
    val uploaderId: String = "",
    val uploaderName: String = "",
    val isNew: Boolean = true,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val sizeBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val likeCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val dislikeCount: Int = 0,
    /** `like`, `dislike`, or null for no reaction — mirrors the `audio_reactions` document. */
    val myReaction: String? = null,
    /** Absolute path of the downloaded copy, or null when only metadata is cached. */
    val cachedPath: String? = null,
)
