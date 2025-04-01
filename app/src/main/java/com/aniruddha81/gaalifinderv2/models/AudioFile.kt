package com.aniruddha81.gaalifinderv2.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_files")
data class AudioFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String = "",
    val path: String = "",
    val source: String = "",
    val isNew: Boolean = true
)