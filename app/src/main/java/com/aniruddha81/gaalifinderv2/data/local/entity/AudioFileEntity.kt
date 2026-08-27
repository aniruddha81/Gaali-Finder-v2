package com.aniruddha81.gaalifinderv2.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Row format for a stored clip.
 *
 * [source] is kept as the original stringly-typed column for backwards compatibility with
 * databases created before v3: `"local"` means the user imported it, anything else is the
 * Appwrite file id it was downloaded from. The mapping to a typed origin happens in
 * `AudioClipMappers`, so nothing above the data layer has to know this convention.
 */
@Entity(
    tableName = "audio_files",
    indices = [
        Index(value = ["fileName"]),
        Index(value = ["source"]),
    ],
)
data class AudioFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String = "",
    val path: String = "",
    val source: String = LOCAL_SOURCE,
    val isNew: Boolean = true,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val sizeBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val addedAt: Long = 0,
) {
    companion object {
        const val LOCAL_SOURCE = "local"
    }
}
