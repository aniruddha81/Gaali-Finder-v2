package com.aniruddha81.gaalifinderv2.ui.home

import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.ClipFilter
import com.aniruddha81.gaalifinderv2.domain.model.ClipSort
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType

/**
 * Every user intent the home screen can express.
 *
 * Funnelling them through one sealed type keeps the composable's parameter list to a single
 * callback and makes the ViewModel's surface obvious at a glance.
 */
sealed interface HomeAction {
    data class TogglePlayback(val clip: AudioClip) : HomeAction
    data object StopPlayback : HomeAction

    data class SearchQueryChanged(val query: String) : HomeAction
    data object OpenSearch : HomeAction
    data object CloseSearch : HomeAction

    data class FilterChanged(val filter: ClipFilter) : HomeAction
    data class SortChanged(val sort: ClipSort) : HomeAction

    data object Refresh : HomeAction

    /** The plus button. What it does depends on whether anyone is signed in. */
    data object UploadRequested : HomeAction
    data class UploadFiles(val files: List<PickedFile>) : HomeAction
    data object FilePickerCancelled : HomeAction
    data class UploadReadFailed(val fileName: String?) : HomeAction

    /** Tapping like or dislike. A guest gets the sign-in prompt instead. */
    data class ReactionTapped(val clip: AudioClip, val reaction: ReactionType) : HomeAction

    data class ShareRequested(val clip: AudioClip) : HomeAction
    data object ShareTargetMissing : HomeAction

    data class ShowClipActions(val clip: AudioClip) : HomeAction
    data object DismissClipActions : HomeAction

    data class DeleteRequested(val clip: AudioClip) : HomeAction
    data object DeleteConfirmed : HomeAction
    data object DeleteDismissed : HomeAction

    data object UpgradeDialogDismissed : HomeAction
    data object UpgradeRequested : HomeAction
    data object UpgradeScreenDismissed : HomeAction

    data object SignInRequested : HomeAction
    data object SignOutRequested : HomeAction
    data object AccountSheetRequested : HomeAction
    data object AccountSheetDismissed : HomeAction
}

/** A file the user picked, already read into memory by the screen. */
data class PickedFile(
    val fileName: String,
    val bytes: ByteArray,
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PickedFile && fileName == other.fileName && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
}
