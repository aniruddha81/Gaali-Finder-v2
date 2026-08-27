package com.aniruddha81.gaalifinderv2.ui.home

import androidx.compose.runtime.Immutable
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.media.PlaybackState
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.ClipFilter
import com.aniruddha81.gaalifinderv2.domain.model.ClipSort

/**
 * Everything the home screen draws, in one immutable object.
 *
 * The screen is a pure function of this state, so there is no way for two widgets to disagree
 * about what is playing or how many clips exist.
 */
@Immutable
data class HomeUiState(
    /** The clips currently visible, after search, filter and sort. */
    val clips: List<AudioClip> = emptyList(),
    /**
     * Size of the whole library, independent of any filter — the header count and the
     * "library is empty" decision must not change just because a filter hid everything.
     */
    val totalClipCount: Int = 0,
    val isInitialLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val isOnline: Boolean = true,
    val searchQuery: String = "",
    val isSearchOpen: Boolean = false,
    val filter: ClipFilter = ClipFilter.All,
    val sort: ClipSort = ClipSort.NameAsc,
    val playback: PlaybackState = PlaybackState.Idle,
    val libraryError: AppError? = null,
    val clipPendingDelete: AudioClip? = null,
    val clipPendingRename: AudioClip? = null,
    val clipInSheet: AudioClip? = null,
) {
    /** True only when the library is genuinely empty, not when a filter hid everything. */
    val isLibraryEmpty: Boolean
        get() = !isInitialLoading && totalClipCount == 0

    val hasActiveQuery: Boolean get() = searchQuery.isNotBlank()

    val isFiltered: Boolean get() = hasActiveQuery || filter != ClipFilter.All
}

/** A one-shot message. Kept out of [HomeUiState] so it cannot re-fire on recomposition. */
sealed interface HomeEffect {
    data class ShowMessage(val message: UiMessage) : HomeEffect
    data class ShareClip(val filePath: String, val displayName: String) : HomeEffect
}

/** Snackbar copy, resolved against string resources at render time. */
sealed interface UiMessage {
    data class FromError(val error: AppError) : UiMessage
    data class FromResource(val resId: Int, val args: List<Any> = emptyList()) : UiMessage
    data class Plural(val resId: Int, val count: Int) : UiMessage

    /**
     * An import can add, skip and fail files in the same batch, so the sentence is assembled
     * from up to three plurals — something a single format string cannot express.
     */
    data class ImportSummary(val added: Int, val skipped: Int, val failed: Int) : UiMessage
}
