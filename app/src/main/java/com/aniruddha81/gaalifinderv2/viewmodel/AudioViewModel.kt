package com.aniruddha81.gaalifinderv2.viewmodel

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniruddha81.gaalifinderv2.appwrite.AppwriteRepository
import com.aniruddha81.gaalifinderv2.data.AudioFile
import com.aniruddha81.gaalifinderv2.data.AudioRepository
import com.aniruddha81.gaalifinderv2.data.FileStorageManager
import com.aniruddha81.gaalifinderv2.ui.SearchWidgetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream


class AudioViewModel(
    private val audioRepository: AudioRepository,
    private val appwriteRepository: AppwriteRepository,
    private val context: Context
) : ViewModel() {

    /*------------ appbar vars starts -----------*/

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


    private val _audioFiles = MutableStateFlow<List<AudioFile>>(emptyList())
    val audioFiles = _audioFiles.asStateFlow()


    init {
        viewModelScope.launch {
            appwriteRepository.fetchAudioFiles(context)
        }
    }

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
            val path = FileStorageManager.saveAudioFile(context, fileName, inputStream)
            audioRepository.addAudioFile(fileName, path, "local")
            loadAudioFiles() // Reload after adding
        }
    }


    //    deletes audio from the room and i.p.s
    fun deleteAudioFile(audio: AudioFile) {
        viewModelScope.launch {
            FileStorageManager.deleteAudioFile(audio.path)
            audioRepository.deleteAudioFile(audio)
            loadAudioFiles()
        }
    }
}
