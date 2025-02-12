package com.aniruddha81.gaalifinderv2.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniruddha81.gaalifinderv2.data.AudioFile
import com.aniruddha81.gaalifinderv2.data.AudioRepository
import com.aniruddha81.gaalifinderv2.data.FileStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.InputStream

class AudioViewModel(
    private val repository: AudioRepository,
    private val context: Context
) : ViewModel() {

    private val _audioFiles = MutableStateFlow<List<AudioFile>>(emptyList())
    val audioFiles: StateFlow<List<AudioFile>> = _audioFiles.asStateFlow()

    //    UI updates
    fun loadAudioFiles() {
        viewModelScope.launch {
            _audioFiles.value = repository.getAudioFiles().value ?: emptyList()
        }
    }

    fun addLocalAudio(fileName: String,byteArray: ByteArray) {
        viewModelScope.launch {
            val inputStream = ByteArrayInputStream(byteArray)
            val path = FileStorageManager.saveAudioFile(context, fileName, inputStream)
            repository.addAudioFile(fileName, path, "local")
            loadAudioFiles() // Reload after adding
        }
    }


    //    deletes audio from the room and i.p.s
    fun deleteAudioFile(audio: AudioFile) {
        viewModelScope.launch {
            FileStorageManager.deleteAudioFile(audio.path)
            repository.deleteAudioFile(audio)
            loadAudioFiles()
        }
    }
}
