package com.aniruddha81.gaalifinderv2.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_files")
data class AudioFile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val path: String,
    val source: String
)
