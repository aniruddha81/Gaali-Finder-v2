package com.aniruddha81.gaalifinderv2.viewmodel

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aniruddha81.gaalifinderv2.appwrite.AppwriteRepository
import com.aniruddha81.gaalifinderv2.models.AudioFile
import com.aniruddha81.gaalifinderv2.data.AudioRepository
import com.aniruddha81.gaalifinderv2.data.FileStorageManagerForIPS
import com.aniruddha81.gaalifinderv2.ui.SearchWidgetState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import javax.inject.Inject


@HiltViewModel
class AudioViewModel @Inject constructor(
    private val audioRepository: AudioRepository,
    private val appwriteRepository: AppwriteRepository,
    application: Application
) : AndroidViewModel(application) {

    private val _audioFiles = MutableStateFlow<List<AudioFile>>(emptyList())
    val audioFiles = _audioFiles.asStateFlow()

    init {
        viewModelScope.launch {
            appwriteRepository.fetchAudioFiles(getApplication())
        }
    }

    private val _searchWidgetState: MutableState<SearchWidgetState> =
        mutableStateOf(value = SearchWidgetState.CLOSED)
    val searchWidgetState: State<SearchWidgetState> = _searchWidgetState

    fun updateSearchWidgetState(newValue: SearchWidgetState) {
        _searchWidgetState.value = newValue
    }

    //    search query state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /*---------------appbar vars ends-------------*/


    //    filtered list
    val filteredAudioFiles = searchQuery.combine(audioFiles) { query, files ->
        if (query.isBlank()) files
        else files.filter { it.fileName.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


    //    UI updates
    fun loadAudioFiles() {
        viewModelScope.launch {
            audioRepository.getAudioFiles().collect { files ->
                _audioFiles.value = files
            }
        }
    }

    fun addLocalAudio(fileName: String, byteArray: ByteArray) {
        viewModelScope.launch {
            val inputStream = ByteArrayInputStream(byteArray)
            val path =
                FileStorageManagerForIPS.saveAudioFileToIPS(getApplication(), fileName, inputStream)
            audioRepository.addAudioFile(
                AudioFile(
                    fileName = fileName,
                    path = path,
                    source = "local"
                )
            )
            loadAudioFiles() // Reload after adding
        }
    }


    //    deletes audio from the room and i.p.s
    fun deleteAudioFile(audio: AudioFile) {
        viewModelScope.launch {
            FileStorageManagerForIPS.deleteAudioFileFromIPS(audio.path)
            audioRepository.deleteAudioFile(audio)
            loadAudioFiles()
        }
    }

    fun renameAudioFile(audioId: Long, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val audioFile = audioRepository.getAudioFileById(audioId)

            val oldFile = File(audioFile.path)

            val newFile = File(oldFile.parent, newName)  // New file path
            val success = oldFile.renameTo(newFile)  // Rename file

            if (success) {
                val updatedAudioFile = audioFile.copy(
                    fileName = newName,
                    path = newFile.absolutePath,
                    source = audioFile.source
                )
                audioRepository.updateAudioFile(updatedAudioFile)  // Update RoomDB
            }
        }
    }
}
