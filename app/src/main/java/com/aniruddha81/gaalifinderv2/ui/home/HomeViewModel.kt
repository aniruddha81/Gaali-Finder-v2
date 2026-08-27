package com.aniruddha81.gaalifinderv2.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniruddha81.gaalifinderv2.R
import com.aniruddha81.gaalifinderv2.core.connectivity.ConnectivityMonitor
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.media.AudioPlayer
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.ClipFilter
import com.aniruddha81.gaalifinderv2.domain.model.ClipOrigin
import com.aniruddha81.gaalifinderv2.domain.model.ClipSort
import com.aniruddha81.gaalifinderv2.domain.repository.AudioClipRepository
import com.aniruddha81.gaalifinderv2.domain.repository.ImportOutcome
import com.aniruddha81.gaalifinderv2.domain.repository.ImportRequest
import com.aniruddha81.gaalifinderv2.domain.repository.SyncOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AudioClipRepository,
    private val player: AudioPlayer,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Effects are buffered and drop-oldest rather than suspending: a burst of failures during a
     * sync must never be able to stall the sync itself waiting for the UI to collect.
     */
    private val _effects = MutableSharedFlow<HomeEffect>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<HomeEffect> = _effects.asSharedFlow()

    /** The full library, before search and filters are applied. */
    private val allClips = MutableStateFlow<List<AudioClip>>(emptyList())

    init {
        observeLibrary()
        observePlayback()
        observeConnectivity(connectivity)
        observeVisibleClips()

        viewModelScope.launch { repository.backfillMissingMetadata() }
        refresh(isUserInitiated = false)
    }

    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.TogglePlayback -> togglePlayback(action.clip)
            HomeAction.StopPlayback -> viewModelScope.launch { player.stop() }

            is HomeAction.SearchQueryChanged -> _uiState.update { it.copy(searchQuery = action.query) }
            HomeAction.OpenSearch -> _uiState.update { it.copy(isSearchOpen = true) }
            HomeAction.CloseSearch ->
                _uiState.update { it.copy(isSearchOpen = false, searchQuery = "") }

            is HomeAction.FilterChanged -> _uiState.update { it.copy(filter = action.filter) }
            is HomeAction.SortChanged -> _uiState.update { it.copy(sort = action.sort) }

            HomeAction.Refresh -> refresh(isUserInitiated = true)

            is HomeAction.ImportFiles -> importFiles(action.files)
            HomeAction.FilePickerCancelled ->
                emitMessage(UiMessage.FromResource(R.string.import_cancelled))

            is HomeAction.ImportFailed ->
                emitMessage(UiMessage.FromResource(R.string.import_read_failed))

            is HomeAction.ShareRequested -> shareClip(action.clip)
            HomeAction.ShareTargetMissing -> emitError(AppError.NoShareTarget)

            is HomeAction.ShowClipActions -> _uiState.update { it.copy(clipInSheet = action.clip) }
            HomeAction.DismissClipActions -> _uiState.update { it.copy(clipInSheet = null) }

            is HomeAction.DeleteRequested ->
                _uiState.update { it.copy(clipPendingDelete = action.clip, clipInSheet = null) }

            HomeAction.DeleteConfirmed -> confirmDelete()
            HomeAction.DeleteDismissed -> _uiState.update { it.copy(clipPendingDelete = null) }

            is HomeAction.RenameRequested ->
                _uiState.update { it.copy(clipPendingRename = action.clip, clipInSheet = null) }

            is HomeAction.RenameConfirmed -> confirmRename(action.newName)
            HomeAction.RenameDismissed -> _uiState.update { it.copy(clipPendingRename = null) }
        }
    }

    // --- Observation -------------------------------------------------------------------------

    private fun observeLibrary() {
        viewModelScope.launch {
            repository.observeClips().collect { clips ->
                allClips.value = clips
                _uiState.update {
                    it.copy(
                        totalClipCount = clips.size,
                        isInitialLoading = false,
                        libraryError = null,
                    )
                }
            }
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            player.state.collect { playback ->
                _uiState.update { it.copy(playback = playback) }
            }
        }
    }

    private fun observeConnectivity(connectivity: ConnectivityMonitor) {
        viewModelScope.launch {
            connectivity.isOnline.collect { online ->
                _uiState.update { it.copy(isOnline = online) }
            }
        }
    }

    /**
     * Search, filter and sort are derived from the library rather than stored, so the visible
     * list can never drift out of step with the database.
     */
    private fun observeVisibleClips() {
        val criteria = _uiState
            .map { Criteria(it.searchQuery, it.filter, it.sort) }
            .distinctUntilChanged()

        viewModelScope.launch {
            combine(allClips, criteria) { clips, (query, filter, sort) ->
                clips.applyFilter(filter).applySearch(query).applySort(sort)
            }.collect { visible ->
                _uiState.update { it.copy(clips = visible) }
            }
        }
    }

    /** The inputs that decide which clips are visible; grouped so re-filtering is skipped
     *  whenever an unrelated part of the state (playback position, dialogs) changes. */
    private data class Criteria(
        val query: String,
        val filter: ClipFilter,
        val sort: ClipSort,
    )

    // --- Playback ----------------------------------------------------------------------------

    private fun togglePlayback(clip: AudioClip) {
        viewModelScope.launch {
            when (val result = player.toggle(clip.id, clip.filePath)) {
                is DataResult.Failure -> emitError(result.error)
                is DataResult.Success -> if (clip.isNew) repository.markClipSeen(clip.id)
            }
        }
    }

    // --- Sync --------------------------------------------------------------------------------

    private fun refresh(isUserInitiated: Boolean) {
        // The slot is claimed synchronously, before launching. Checking the flag inside the
        // coroutine would let two quick pull-to-refresh gestures both past the guard, since
        // neither coroutine has started by the time the second one is dispatched.
        val previous = _uiState.getAndUpdate { it.copy(isSyncing = true) }
        if (previous.isSyncing) return

        viewModelScope.launch {
            val result = repository.syncRemoteClips()
            _uiState.update { it.copy(isSyncing = false) }

            when (result) {
                is DataResult.Success -> if (isUserInitiated) reportSync(result.data)
                is DataResult.Failure -> {
                    // A silent start-up sync should not nag the user about being offline; only
                    // a pull-to-refresh they performed themselves deserves an answer.
                    if (isUserInitiated && result.error != AppError.RemoteNotConfigured) {
                        emitError(result.error)
                    }
                }
            }
        }
    }

    private fun reportSync(outcome: SyncOutcome) {
        when {
            outcome.hasNewClips -> emitMessage(
                UiMessage.Plural(R.plurals.sync_downloaded, outcome.downloaded)
            )

            outcome.failed > 0 -> emitMessage(
                UiMessage.Plural(R.plurals.sync_partial_failure, outcome.failed)
            )

            else -> emitMessage(UiMessage.FromResource(R.string.sync_up_to_date))
        }
    }

    // --- Import ------------------------------------------------------------------------------

    private fun importFiles(files: List<PickedFile>) {
        if (files.isEmpty()) {
            emitMessage(UiMessage.FromResource(R.string.import_cancelled))
            return
        }

        viewModelScope.launch {
            var added = 0
            var skipped = 0
            var failed = 0

            // Each file is imported independently so one unreadable pick cannot abort the batch.
            files.forEach { file ->
                when (val result = repository.importClip(ImportRequest(file.fileName, file.bytes))) {
                    is DataResult.Failure -> failed++
                    is DataResult.Success -> when (result.data) {
                        is ImportOutcome.Added -> added++
                        is ImportOutcome.AlreadyExists -> skipped++
                    }
                }
            }

            emitMessage(UiMessage.ImportSummary(added = added, skipped = skipped, failed = failed))
        }
    }

    // --- Share, delete, rename ---------------------------------------------------------------

    private fun shareClip(clip: AudioClip) {
        viewModelScope.launch {
            // Sharing hands the file to another app; stop first so the clip is not still open.
            player.stop()
            _uiState.update { it.copy(clipInSheet = null) }
            if (clip.isNew) repository.markClipSeen(clip.id)
            _effects.tryEmit(HomeEffect.ShareClip(clip.filePath, clip.displayName))
        }
    }

    private fun confirmDelete() {
        val clip = _uiState.value.clipPendingDelete ?: return
        _uiState.update { it.copy(clipPendingDelete = null) }

        viewModelScope.launch {
            if (_uiState.value.playback.clipId == clip.id) player.stop()

            when (val result = repository.deleteClip(clip)) {
                is DataResult.Failure -> emitError(result.error)
                is DataResult.Success -> emitMessage(
                    UiMessage.FromResource(R.string.delete_success, listOf(clip.displayName))
                )
            }
        }
    }

    private fun confirmRename(newName: String) {
        val clip = _uiState.value.clipPendingRename ?: return

        viewModelScope.launch {
            when (val result = repository.renameClip(clip.id, newName)) {
                is DataResult.Failure -> emitError(result.error)
                is DataResult.Success -> {
                    _uiState.update { it.copy(clipPendingRename = null) }
                    emitMessage(
                        UiMessage.FromResource(
                            R.string.rename_success,
                            listOf(result.data.displayName),
                        )
                    )
                }
            }
        }
    }

    // --- Helpers -----------------------------------------------------------------------------

    private fun emitError(error: AppError) {
        _effects.tryEmit(HomeEffect.ShowMessage(UiMessage.FromError(error)))
    }

    private fun emitMessage(message: UiMessage) {
        _effects.tryEmit(HomeEffect.ShowMessage(message))
    }

    override fun onCleared() {
        // The player outlives this ViewModel, so it must be told explicitly to let go.
        player.stopBlocking()
    }
}

// --- Pure list operations, kept out of the ViewModel so they are trivially testable -----------

internal fun List<AudioClip>.applyFilter(filter: ClipFilter): List<AudioClip> = when (filter) {
    ClipFilter.All -> this
    ClipFilter.New -> filter { it.isNew }
    ClipFilter.Downloaded -> filter { it.origin is ClipOrigin.Remote }
    ClipFilter.MyClips -> filter { it.origin is ClipOrigin.Local }
}

internal fun List<AudioClip>.applySearch(query: String): List<AudioClip> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return filter { it.displayName.contains(trimmed, ignoreCase = true) }
}

internal fun List<AudioClip>.applySort(sort: ClipSort): List<AudioClip> = when (sort) {
    ClipSort.NameAsc -> sortedBy { it.displayName.lowercase() }
    ClipSort.RecentFirst -> sortedByDescending { it.addedAt }
    ClipSort.LongestFirst -> sortedByDescending { it.durationMs }
}
