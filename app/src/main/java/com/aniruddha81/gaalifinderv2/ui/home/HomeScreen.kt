package com.aniruddha81.gaalifinderv2.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aniruddha81.gaalifinderv2.R
import com.aniruddha81.gaalifinderv2.auth.AuthEvent
import com.aniruddha81.gaalifinderv2.auth.AuthViewModel
import com.aniruddha81.gaalifinderv2.domain.model.ClipFilter
import com.aniruddha81.gaalifinderv2.ui.common.resolve
import com.aniruddha81.gaalifinderv2.ui.home.components.AccountSheet
import com.aniruddha81.gaalifinderv2.ui.home.components.AudioClipCard
import com.aniruddha81.gaalifinderv2.ui.home.components.ClipActionsSheet
import com.aniruddha81.gaalifinderv2.ui.home.components.ClipFilterRow
import com.aniruddha81.gaalifinderv2.ui.home.components.DeleteClipDialog
import com.aniruddha81.gaalifinderv2.ui.home.components.EmptyState
import com.aniruddha81.gaalifinderv2.ui.home.components.HomeTopBar
import com.aniruddha81.gaalifinderv2.ui.home.components.LoadingGrid
import com.aniruddha81.gaalifinderv2.ui.home.components.NoResultsState
import com.aniruddha81.gaalifinderv2.ui.home.components.OfflineBanner
import com.aniruddha81.gaalifinderv2.ui.home.components.QuotaExceededDialog
import com.aniruddha81.gaalifinderv2.ui.home.components.UpgradePlaceholderDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        uiState = uiState,
        effects = viewModel.effects,
        authEvents = authViewModel.events,
        onAction = viewModel::onAction,
        onSignIn = authViewModel::signIn,
        onSignOut = authViewModel::signOut,
    )
}

/**
 * Stateless screen body.
 *
 * Taking state, effect streams and plain callbacks keeps every rendering decision testable and
 * previewable without a ViewModel or Hilt graph behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    uiState: HomeUiState,
    effects: Flow<HomeEffect>,
    authEvents: Flow<AuthEvent>,
    onAction: (HomeAction) -> Unit,
    onSignIn: (ComponentActivity, () -> Unit) -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) {
            onAction(HomeAction.FilePickerCancelled)
            return@rememberLauncherForActivityResult
        }
        // Reading happens off the picker callback but on a coroutine, so a large selection does
        // not block the frame that dismissed the picker.
        scope.launch {
            val picked = uris.mapNotNull { uri -> context.readPickedFile(uri) }
            if (picked.isEmpty()) onAction(HomeAction.UploadReadFailed(null))
            else onAction(HomeAction.UploadFiles(picked))
        }
    }

    // Effects are collected once and never replayed, so a rotation cannot resurface an old
    // snackbar the user already dismissed.
    LaunchedEffect(Unit) {
        effects.collect { effect ->
            when (effect) {
                is HomeEffect.ShowMessage -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(effect.message.resolve(context))
                }

                is HomeEffect.ShareClip -> {
                    val launched = context.shareAudio(effect.filePath, effect.displayName)
                    if (!launched) onAction(HomeAction.ShareTargetMissing)
                }

                HomeEffect.OpenFilePicker -> filePicker.launch(AUDIO_MIME_TYPES)

                is HomeEffect.RequestSignIn -> {
                    // OAuth needs the hosting activity, which only the composition can reach.
                    val activity = context.findComponentActivity()
                    if (activity == null) {
                        onAction(HomeAction.ShareTargetMissing)
                    } else {
                        onSignIn(activity) {
                            // Sending them straight into the picker means the plus button takes
                            // one tap for a guest too, not two.
                            if (effect.thenOpenPicker) filePicker.launch(AUDIO_MIME_TYPES)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        authEvents.collect { event ->
            val text = when (event) {
                is AuthEvent.SignedIn -> context.getString(R.string.signed_in_as, event.displayName)
                AuthEvent.SignedOut -> context.getString(R.string.signed_out)
                is AuthEvent.Failed -> UiMessage.FromError(event.error).resolve(context)
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    Scaffold(
        topBar = {
            HomeTopBar(
                isSearchOpen = uiState.isSearchOpen,
                query = uiState.searchQuery,
                clipCount = uiState.totalClipCount,
                sort = uiState.sort,
                isSignedIn = uiState.isSignedIn,
                onQueryChange = { onAction(HomeAction.SearchQueryChanged(it)) },
                onOpenSearch = { onAction(HomeAction.OpenSearch) },
                onCloseSearch = { onAction(HomeAction.CloseSearch) },
                onSortChange = { onAction(HomeAction.SortChanged(it)) },
                onAccountClick = {
                    if (uiState.isSignedIn) onAction(HomeAction.AccountSheetRequested)
                    else onAction(HomeAction.SignInRequested)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // A guest is not blocked here: the ViewModel turns the tap into a sign-in
                // prompt, so the button always does something visible.
                onClick = { onAction(HomeAction.UploadRequested) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = {
                    if (uiState.isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    }
                },
                text = {
                    Text(
                        stringResource(
                            when {
                                uiState.isUploading -> R.string.uploading
                                uiState.isSignedIn -> R.string.cd_add_clips
                                else -> R.string.action_sign_in
                            }
                        )
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedVisibility(
                visible = !uiState.isOnline,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                OfflineBanner()
            }

            AnimatedVisibility(visible = !uiState.isLibraryEmpty && !uiState.isInitialLoading) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    ClipFilterRow(
                        selected = uiState.filter,
                        availableFilters = uiState.availableFilters(),
                        onFilterChange = { onAction(HomeAction.FilterChanged(it)) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            PullToRefreshBox(
                isRefreshing = uiState.isSyncing,
                onRefresh = { onAction(HomeAction.Refresh) },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.isInitialLoading -> LoadingGrid()

                    uiState.isLibraryEmpty -> EmptyState(
                        title = stringResource(R.string.empty_library_title),
                        body = stringResource(R.string.empty_library_body),
                        actionLabel = stringResource(R.string.empty_library_action),
                        onAction = { onAction(HomeAction.UploadRequested) },
                    )

                    uiState.clips.isEmpty() -> NoResultsState(
                        query = uiState.searchQuery,
                        onClearFilters = {
                            onAction(HomeAction.SearchQueryChanged(""))
                            onAction(HomeAction.FilterChanged(ClipFilter.All))
                        },
                    )

                    else -> ClipGrid(
                        uiState = uiState,
                        state = gridState,
                        onAction = onAction,
                    )
                }
            }
        }
    }

    // Dialogs are driven from state, so the correct one survives a configuration change.
    uiState.clipInSheet?.let { clip ->
        ClipActionsSheet(
            clip = clip,
            canDelete = clip.isDeletableBy(uiState.currentUserId),
            onShare = { onAction(HomeAction.ShareRequested(clip)) },
            onDelete = { onAction(HomeAction.DeleteRequested(clip)) },
            onDismiss = { onAction(HomeAction.DismissClipActions) },
        )
    }

    uiState.clipPendingDelete?.let { clip ->
        DeleteClipDialog(
            clip = clip,
            onConfirm = { onAction(HomeAction.DeleteConfirmed) },
            onDismiss = { onAction(HomeAction.DeleteDismissed) },
        )
    }

    uiState.quotaBlock?.let { error ->
        QuotaExceededDialog(
            error = error,
            onUpgrade = { onAction(HomeAction.UpgradeRequested) },
            onDismiss = { onAction(HomeAction.UpgradeDialogDismissed) },
        )
    }

    if (uiState.isUpgradeScreenOpen) {
        UpgradePlaceholderDialog(onDismiss = { onAction(HomeAction.UpgradeScreenDismissed) })
    }

    if (uiState.isAccountSheetOpen) {
        uiState.authState.userOrNull?.let { user ->
            AccountSheet(
                user = user,
                usage = uiState.storageUsage,
                onSignOut = {
                    onAction(HomeAction.SignOutRequested)
                    onSignOut()
                },
                onDismiss = { onAction(HomeAction.AccountSheetDismissed) },
            )
        }
    }
}

@Composable
private fun ClipGrid(
    uiState: HomeUiState,
    state: LazyStaggeredGridState,
    onAction: (HomeAction) -> Unit,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = 150.dp),
        state = state,
        modifier = Modifier.fillMaxSize(),
        // Bottom padding clears the FAB, replacing the old 500dp spacer item that also broke
        // the grid's scroll extent.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
    ) {
        items(items = uiState.clips, key = { it.id }) { clip ->
            AudioClipCard(
                clip = clip,
                playback = uiState.playback,
                onToggle = { onAction(HomeAction.TogglePlayback(clip)) },
                onLongPress = { onAction(HomeAction.ShowClipActions(clip)) },
                onReact = { onAction(HomeAction.ReactionTapped(clip, it)) },
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(220),
                    placementSpec = tween(260),
                ),
            )
        }

        // Attribution as a real footer row rather than an overlay pinned above the grid, which
        // used to sit on top of the last cards.
        item(key = "credit", span = StaggeredGridItemSpan.FullLine) {
            Text(
                text = stringResource(R.string.developer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            )
        }
    }
}

/**
 * Only offers a filter when the catalogue actually contains clips it would match.
 *
 * The currently selected filter is always kept, so the chip the user is standing on cannot
 * vanish from under them when its last matching clip is deleted.
 */
private fun HomeUiState.availableFilters(): List<ClipFilter> = buildList {
    add(ClipFilter.All)
    if (filter == ClipFilter.New || clips.any { it.isNew }) add(ClipFilter.New)
    if (filter == ClipFilter.Downloaded || clips.any { it.isDownloaded }) {
        add(ClipFilter.Downloaded)
    }
    // "My uploads" is meaningless to a guest, who cannot have uploaded anything.
    if (isSignedIn && (filter == ClipFilter.MyClips || clips.any { it.uploaderId == currentUserId })) {
        add(ClipFilter.MyClips)
    }
}

// --- Platform interop ------------------------------------------------------------------------

private val AUDIO_MIME_TYPES = arrayOf("audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav", "audio/*")

/**
 * Walks the `ContextWrapper` chain to the hosting activity.
 *
 * `LocalContext` inside a Compose tree is usually the activity, but a themed wrapper can sit in
 * between, so casting directly would throw for some callers.
 */
private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/**
 * Reads a picked document into memory.
 *
 * Returns null instead of throwing: a single unreadable pick should be counted and reported,
 * not abort the whole batch.
 */
private suspend fun Context.readPickedFile(uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
    runCatching {
        // Reject anything far past the upload cap before allocating for it. This is a cheap
        // guard against an OOM, not the limit itself — the repository enforces the real 200 KB
        // rule and produces the message that names the actual size.
        val declaredSize = sizeOf(uri)
        if (declaredSize != null && declaredSize > MAX_READ_BYTES) return@runCatching null

        val bytes = contentResolver.openInputStream(uri)?.use { stream ->
            stream.readAtMost(MAX_READ_BYTES)
        }
        if (bytes == null || bytes.isEmpty()) null else PickedFile(displayNameOf(uri), bytes)
    }.getOrNull()
}

/** Reads up to [limit] bytes, returning null if the stream turns out to be longer. */
private fun InputStream.readAtMost(limit: Long): ByteArray? {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(DEFAULT_CHUNK_BYTES)
    var total = 0L

    while (true) {
        val read = read(chunk)
        if (read == -1) break
        total += read
        if (total > limit) return null
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

private fun Context.sizeOf(uri: Uri): Long? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }
}.getOrNull()

/**
 * Generous relative to the 200 KB upload cap, on purpose: a slightly-too-large file must still
 * be read so the user can be told exactly how far over it is.
 */
private const val MAX_READ_BYTES = 8L * 1024 * 1024
private const val DEFAULT_CHUNK_BYTES = 8 * 1024

private fun Context.displayNameOf(uri: Uri): String {
    val fromCursor = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    return fromCursor?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "clip.mp3"
}

/**
 * Returns false when the share could not be started — a missing file, no app able to receive
 * audio, or a FileProvider path that does not cover the clip — so the caller can tell the user
 * instead of the tap appearing to do nothing.
 */
private fun Context.shareAudio(filePath: String, displayName: String): Boolean {
    val file = File(filePath)
    if (!file.exists()) return false

    return runCatching {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser =
            Intent.createChooser(intent, getString(R.string.share_chooser_title, displayName))
        startActivity(chooser)
        true
    }.getOrDefault(false)
}
