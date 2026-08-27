package com.aniruddha81.gaalifinderv2.ui.home

import androidx.compose.runtime.Immutable
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.media.PlaybackState
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.AuthState
import com.aniruddha81.gaalifinderv2.domain.model.ClipFilter
import com.aniruddha81.gaalifinderv2.domain.model.ClipSort
import com.aniruddha81.gaalifinderv2.domain.repository.StorageUsage

/**
 * Everything the home screen draws, in one immutable object.
 *
 * The screen is a pure function of this state, so there is no way for two widgets to disagree
 * about what is playing, who is signed in, or how much quota is left.
 */
@Immutable
data class HomeUiState(
    /** The clips currently visible, after search, filter and sort. */
    val clips: List<AudioClip> = emptyList(),
    /**
     * Size of the whole catalogue, independent of any filter — the header count and the
     * "nothing here" decision must not change just because a filter hid everything.
     */
    val totalClipCount: Int = 0,
    val isInitialLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val isUploading: Boolean = false,
    val isOnline: Boolean = true,
    val authState: AuthState = AuthState.Unknown,
    val searchQuery: String = "",
    val isSearchOpen: Boolean = false,
    val filter: ClipFilter = ClipFilter.All,
    val sort: ClipSort = ClipSort.RecentFirst,
    val playback: PlaybackState = PlaybackState.Idle,
    val libraryError: AppError? = null,
    val storageUsage: StorageUsage? = null,
    val clipPendingDelete: AudioClip? = null,
    val clipInSheet: AudioClip? = null,
    /** Set when an upload was blocked by the quota, so the upgrade dialog can explain why. */
    val quotaBlock: AppError.QuotaExceeded? = null,
    val isAccountSheetOpen: Boolean = false,
    val isUpgradeScreenOpen: Boolean = false,
) {
    val isSignedIn: Boolean get() = authState.isSignedIn

    val currentUserId: String? get() = authState.userOrNull?.id

    val isPremium: Boolean get() = storageUsage?.isPremium == true

    /** True only when the catalogue is genuinely empty, not when a filter hid everything. */
    val isLibraryEmpty: Boolean
        get() = !isInitialLoading && totalClipCount == 0

    val hasActiveQuery: Boolean get() = searchQuery.isNotBlank()

    val isFiltered: Boolean get() = hasActiveQuery || filter != ClipFilter.All

    /** Guests may listen but not upload, so the plus button signs them in first. */
    val canUpload: Boolean get() = isSignedIn && !isUploading
}

/** A one-shot message. Kept out of [HomeUiState] so it cannot re-fire on recomposition. */
sealed interface HomeEffect {
    data class ShowMessage(val message: UiMessage) : HomeEffect
    data class ShareClip(val filePath: String, val displayName: String) : HomeEffect

    /** Asks the screen to launch Google sign-in, which needs the host activity. */
    data class RequestSignIn(val thenOpenPicker: Boolean) : HomeEffect

    /** Asks the screen to open the system file picker. */
    data object OpenFilePicker : HomeEffect
}

/** Snackbar copy, resolved against string resources at render time. */
sealed interface UiMessage {
    data class FromError(val error: AppError) : UiMessage
    data class FromResource(val resId: Int, val args: List<Any> = emptyList()) : UiMessage
    data class Plural(val resId: Int, val count: Int) : UiMessage

    /**
     * An upload batch can add, reject and fail files at once, so the sentence is assembled from
     * up to three plurals — something a single format string cannot express.
     */
    data class UploadSummary(val added: Int, val tooLarge: Int, val failed: Int) : UiMessage
}
