package com.aniruddha81.gaalifinderv2.model

import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class AudioFile(
    val fileName: String,
    @PrimaryKey
    val uri: Uri
)
