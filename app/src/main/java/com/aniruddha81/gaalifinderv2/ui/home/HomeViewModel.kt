package com.aniruddha81.gaalifinderv2.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniruddha81.gaalifinderv2.R
import com.aniruddha81.gaalifinderv2.core.connectivity.ConnectivityMonitor
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.media.AudioPlayer
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.AuthState
import com.aniruddha81.gaalifinderv2.domain.model.ClipFilter
import com.aniruddha81.gaalifinderv2.domain.model.ClipSort
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType
import com.aniruddha81.gaalifinderv2.domain.model.StorageQuota
import com.aniruddha81.gaalifinderv2.domain.repository.AudioClipRepository
import com.aniruddha81.gaalifinderv2.domain.repository.AuthRepository
import com.aniruddha81.gaalifinderv2.domain.repository.SyncOutcome
import com.aniruddha81.gaalifinderv2.domain.repository.UploadClipRequest
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
    private val authRepository: AuthRepository,
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

    /** The full catalogue, before search and filters are applied. */
    private val allClips = MutableStateFlow<List<AudioClip>>(emptyList())

    init {
        observeLibrary()
        observePlayback()
        observeConnectivity(connectivity)
        observeAuth()
        observeVisibleClips()

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

            HomeAction.UploadRequested -> requestUpload()
            is HomeAction.UploadFiles -> uploadFiles(action.files)
            HomeAction.FilePickerCancelled ->
                emitMessage(UiMessage.FromResource(R.string.import_cancelled))

            is HomeAction.UploadReadFailed ->
                emitMessage(UiMessage.FromResource(R.string.import_read_failed))

            is HomeAction.ReactionTapped -> react(action.clip, action.reaction)

            is HomeAction.ShareRequested -> shareClip(action.clip)
            HomeAction.ShareTargetMissing -> emitError(AppError.NoShareTarget)

            is HomeAction.ShowClipActions -> _uiState.update { it.copy(clipInSheet = action.clip) }
            HomeAction.DismissClipActions -> _uiState.update { it.copy(clipInSheet = null) }

            is HomeAction.DeleteRequested ->
                _uiState.update { it.copy(clipPendingDelete = action.clip, clipInSheet = null) }

            HomeAction.DeleteConfirmed -> confirmDelete()
            HomeAction.DeleteDismissed -> _uiState.update { it.copy(clipPendingDelete = null) }

            HomeAction.UpgradeDialogDismissed -> _uiState.update { it.copy(quotaBlock = null) }
            HomeAction.UpgradeRequested ->
                _uiState.update { it.copy(quotaBlock = null, isUpgradeScreenOpen = true) }

            HomeAction.UpgradeScreenDismissed ->
                _uiState.update { it.copy(isUpgradeScreenOpen = false) }

            HomeAction.SignInRequested ->
                _effects.tryEmit(HomeEffect.RequestSignIn(thenOpenPicker = false))

            HomeAction.SignOutRequested -> _uiState.update { it.copy(isAccountSheetOpen = false) }

            HomeAction.AccountSheetRequested -> _uiState.update { it.copy(isAccountSheetOpen = true) }
            HomeAction.AccountSheetDismissed ->
                _uiState.update { it.copy(isAccountSheetOpen = false) }
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
                        // Only clips clear the error: the mirror re-emitting an empty list is
                        // exactly what happens when a sync fails, so clearing unconditionally
                        // would erase the reason the grid is empty.
                        libraryError = if (clips.isEmpty()) it.libraryError else null,
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
     * Follows the session, and re-syncs whenever the signed-in user changes.
     *
     * Reactions are per-account, so the catalogue has to be re-read on both sign-in and
     * sign-out — otherwise the previous user's likes would stay highlighted on the cards.
     */
    private fun observeAuth() {
        viewModelScope.launch {
            var previousUserId: String? = null
            var seenFirstState = false

            authRepository.authState.collect { state ->
                _uiState.update { it.copy(authState = state) }

                if (state is AuthState.Unknown) return@collect

                val userId = state.userOrNull?.id
                if (seenFirstState && userId != previousUserId) {
                    refresh(isUserInitiated = false)
                }
                previousUserId = userId
                seenFirstState = true

                refreshStorageUsage()
            }
        }
    }

    /**
     * Search, filter and sort are derived from the catalogue rather than stored, so the visible
     * list can never drift out of step with the mirror.
     */
    private fun observeVisibleClips() {
        val criteria = _uiState
            .map { Criteria(it.searchQuery, it.filter, it.sort, it.currentUserId) }
            .distinctUntilChanged()

        viewModelScope.launch {
            combine(allClips, criteria) { clips, (query, filter, sort, userId) ->
                clips.applyFilter(filter, userId).applySearch(query).applySort(sort)
            }.collect { visible ->
                _uiState.update { it.copy(clips = visible) }
            }
        }
    }

    /**
     * The inputs that decide which clips are visible; grouped so re-filtering is skipped
     * whenever an unrelated part of the state (playback position, dialogs) changes.
     */
    private data class Criteria(
        val query: String,
        val filter: ClipFilter,
        val sort: ClipSort,
        val userId: String?,
    )

    // --- Playback ----------------------------------------------------------------------------

    /**
     * Downloads the audio if this is its first play, then hands the local path to the player.
     *
     * The download is what the "preparing" spinner on the card is covering, so the clip is
     * marked seen only once it actually starts.
     */
    private fun togglePlayback(clip: AudioClip) {
        viewModelScope.launch {
            // Stopping needs no download, so it is handled before the fetch rather than going
            // through `toggle` — otherwise pausing an uncached clip would first download it.
            if (_uiState.value.playback.clipId == clip.id && _uiState.value.playback.isActive) {
                player.stop()
                return@launch
            }

            val path = when (val result = repository.ensurePlayable(clip)) {
                is DataResult.Failure -> {
                    emitError(result.error)
                    return@launch
                }

                is DataResult.Success -> result.data
            }

            when (val result = player.play(clip.id, path)) {
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
            val result = repository.syncCatalogue()
            _uiState.update { it.copy(isSyncing = false) }

            when (result) {
                is DataResult.Success -> {
                    _uiState.update { it.copy(libraryError = null) }
                    if (isUserInitiated) reportSync(result.data)
                }

                is DataResult.Failure -> {
                    // A silent start-up sync should not nag the user about being offline; only
                    // a pull-to-refresh they performed themselves deserves an answer.
                    if (isUserInitiated && result.error != AppError.RemoteNotConfigured) {
                        emitError(result.error)
                    }
                    // A snackbar the user never triggered is easy to miss, and an empty grid with
                    // no explanation reads as "there are no clips" rather than "the sync failed".
                    // Recording it lets the empty state say which one actually happened.
                    _uiState.update { it.copy(libraryError = result.error) }
                }
            }
        }
    }

    private fun reportSync(outcome: SyncOutcome) {
        when {
            outcome.hasNewClips ->
                emitMessage(UiMessage.Plural(R.plurals.sync_downloaded, outcome.added))

            else -> emitMessage(UiMessage.FromResource(R.string.sync_up_to_date))
        }
    }

    private fun refreshStorageUsage() {
        val userId = _uiState.value.currentUserId
        if (userId == null) {
            _uiState.update { it.copy(storageUsage = null) }
            return
        }

        viewModelScope.launch {
            repository.storageUsage(userId).let { result ->
                if (result is DataResult.Success) {
                    // Only apply it if the same user is still signed in — a sign-out mid-read
                    // must not leave their usage on screen.
                    _uiState.update { state ->
                        if (state.currentUserId == userId) {
                            state.copy(storageUsage = result.data)
                        } else {
                            state
                        }
                    }
                }
            }
        }
    }

    // --- Upload ------------------------------------------------------------------------------

    /** The plus button: guests are sent through sign-in first, and land back in the picker. */
    private fun requestUpload() {
        if (_uiState.value.isUploading) return

        if (!_uiState.value.isSignedIn) {
            _effects.tryEmit(HomeEffect.RequestSignIn(thenOpenPicker = true))
            return
        }

        _effects.tryEmit(HomeEffect.OpenFilePicker)
    }

    private fun uploadFiles(files: List<PickedFile>) {
        if (files.isEmpty()) {
            emitMessage(UiMessage.FromResource(R.string.import_cancelled))
            return
        }

        val user = _uiState.value.authState.userOrNull
        if (user == null) {
            emitError(AppError.NotSignedIn)
            return
        }

        val previous = _uiState.getAndUpdate { it.copy(isUploading = true) }
        if (previous.isUploading) return

        viewModelScope.launch {
            var added = 0
            var tooLarge = 0
            var failed = 0
            var quotaBlock: AppError.QuotaExceeded? = null

            // Each file is uploaded independently so one rejection cannot abort the batch.
            for (file in files) {
                val result = repository.uploadClip(
                    UploadClipRequest(
                        fileName = file.fileName,
                        bytes = file.bytes,
                        uploaderId = user.id,
                        uploaderName = user.displayName,
                    )
                )

                when (result) {
                    is DataResult.Success -> added++
                    is DataResult.Failure -> when (val error = result.error) {
                        is AppError.FileTooLarge -> {
                            tooLarge++
                            // Named individually, since the user needs to know *which* file and
                            // by how much — a bare count would not tell them what to trim.
                            emitMessage(
                                UiMessage.FromResource(
                                    R.string.error_file_too_large_named,
                                    listOf(
                                        file.fileName,
                                        error.sizeBytes.toKilobytes(),
                                        StorageQuota.MAX_FILE_BYTES.toKilobytes(),
                                    ),
                                )
                            )
                        }

                        is AppError.QuotaExceeded -> {
                            // The rest of the batch cannot fit either, so stop here and let the
                            // upgrade dialog explain once rather than per file.
                            quotaBlock = error
                        }

                        else -> failed++
                    }
                }

                if (quotaBlock != null) break
            }

            _uiState.update { it.copy(isUploading = false, quotaBlock = quotaBlock) }

            if (added > 0 || tooLarge > 0 || failed > 0) {
                emitMessage(
                    UiMessage.UploadSummary(added = added, tooLarge = tooLarge, failed = failed)
                )
            }

            if (added > 0) refreshStorageUsage()
        }
    }

    // --- Reactions ---------------------------------------------------------------------------

    /** Guests get the sign-in prompt rather than a tap that silently does nothing. */
    private fun react(clip: AudioClip, tapped: ReactionType) {
        val userId = _uiState.value.currentUserId
        if (userId == null) {
            _effects.tryEmit(HomeEffect.RequestSignIn(thenOpenPicker = false))
            return
        }

        viewModelScope.launch {
            when (val result = repository.react(clip, userId, tapped)) {
                is DataResult.Failure -> emitError(result.error)
                is DataResult.Success -> Unit // Room re-emits; nothing else to do.
            }
        }
    }

    // --- Share, delete -----------------------------------------------------------------------

    private fun shareClip(clip: AudioClip) {
        viewModelScope.launch {
            // Sharing hands the file to another app, so it has to exist locally first.
            player.stop()
            _uiState.update { it.copy(clipInSheet = null) }

            val path = when (val result = repository.ensurePlayable(clip)) {
                is DataResult.Failure -> {
                    emitError(result.error)
                    return@launch
                }

                is DataResult.Success -> result.data
            }

            if (clip.isNew) repository.markClipSeen(clip.id)
            _effects.tryEmit(HomeEffect.ShareClip(path, clip.displayName))
        }
    }

    private fun confirmDelete() {
        val clip = _uiState.value.clipPendingDelete ?: return
        _uiState.update { it.copy(clipPendingDelete = null) }

        viewModelScope.launch {
            if (_uiState.value.playback.clipId == clip.id) player.stop()

            when (val result = repository.deleteClip(clip)) {
                is DataResult.Failure -> emitError(result.error)
                is DataResult.Success -> {
                    emitMessage(
                        UiMessage.FromResource(R.string.delete_success, listOf(clip.displayName))
                    )
                    refreshStorageUsage()
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

/**
 * [ClipFilter.MyClips] now means "clips I uploaded", matched on uploader id, rather than the
 * old "files on this device" — every clip lives in the shared catalogue now.
 */
internal fun List<AudioClip>.applyFilter(
    filter: ClipFilter,
    currentUserId: String?,
): List<AudioClip> = when (filter) {
    ClipFilter.All -> this
    ClipFilter.New -> filter { it.isNew }
    ClipFilter.Downloaded -> filter { it.isDownloaded }
    ClipFilter.MyClips ->
        if (currentUserId == null) emptyList() else filter { it.uploaderId == currentUserId }
}

internal fun List<AudioClip>.applySearch(query: String): List<AudioClip> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return filter {
        it.displayName.contains(trimmed, ignoreCase = true) ||
            it.uploaderName.contains(trimmed, ignoreCase = true)
    }
}

internal fun List<AudioClip>.applySort(sort: ClipSort): List<AudioClip> = when (sort) {
    ClipSort.NameAsc -> sortedBy { it.displayName.lowercase() }
    ClipSort.RecentFirst -> sortedByDescending { it.createdAt }
    ClipSort.LongestFirst -> sortedByDescending { it.durationMs }
    // Ties broken by recency, so equally-scored clips still have a stable, sensible order.
    ClipSort.MostPopular -> sortedWith(
        compareByDescending<AudioClip> { it.netScore }.thenByDescending { it.createdAt }
    )
}

/** Rounded up, so a 204,801-byte file never reads as exactly the 200 KB limit. */
internal fun Long.toKilobytes(): Long = (this + 1023) / 1024
