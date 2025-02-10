package com.aniruddha81.gaalifinderv2.model

import android.net.Uri
import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun uriToString(uri: Uri): String {
        return uri.toString()
    }

    @TypeConverter
    fun stringToUri(uriString: String): Uri {
        return Uri.parse(uriString)
    }
}