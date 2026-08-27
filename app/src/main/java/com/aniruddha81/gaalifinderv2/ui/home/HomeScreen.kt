package com.aniruddha81.gaalifinderv2.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.aniruddha81.gaalifinderv2.domain.model.ClipFilter
import com.aniruddha81.gaalifinderv2.domain.model.ClipOrigin
import com.aniruddha81.gaalifinderv2.ui.common.resolve
import com.aniruddha81.gaalifinderv2.ui.home.components.AudioClipCard
import com.aniruddha81.gaalifinderv2.ui.home.components.ClipActionsSheet
import com.aniruddha81.gaalifinderv2.ui.home.components.ClipFilterRow
import com.aniruddha81.gaalifinderv2.ui.home.components.DeleteClipDialog
import com.aniruddha81.gaalifinderv2.ui.home.components.EmptyState
import com.aniruddha81.gaalifinderv2.ui.home.components.HomeTopBar
import com.aniruddha81.gaalifinderv2.ui.home.components.LoadingGrid
import com.aniruddha81.gaalifinderv2.ui.home.components.NoResultsState
import com.aniruddha81.gaalifinderv2.ui.home.components.OfflineBanner
import com.aniruddha81.gaalifinderv2.ui.home.components.RenameClipDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        uiState = uiState,
        effects = viewModel.effects,
        onAction = viewModel::onAction,
    )
}

/**
 * Stateless screen body.
 *
 * Taking state, an effect stream and a single action callback keeps every rendering decision
 * testable and previewable without a ViewModel or Hilt graph behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    uiState: HomeUiState,
    effects: Flow<HomeEffect>,
    onAction: (HomeAction) -> Unit,
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
            if (picked.isEmpty()) onAction(HomeAction.ImportFailed(null))
            else onAction(HomeAction.ImportFiles(picked))
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
            }
        }
    }

    Scaffold(
        topBar = {
            HomeTopBar(
                isSearchOpen = uiState.isSearchOpen,
                query = uiState.searchQuery,
                clipCount = uiState.totalClipCount,
                sort = uiState.sort,
                onQueryChange = { onAction(HomeAction.SearchQueryChanged(it)) },
                onOpenSearch = { onAction(HomeAction.OpenSearch) },
                onCloseSearch = { onAction(HomeAction.CloseSearch) },
                onSortChange = { onAction(HomeAction.SortChanged(it)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePicker.launch(AUDIO_MIME_TYPES) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.cd_add_clips)) },
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
                        onAction = { filePicker.launch(AUDIO_MIME_TYPES) },
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
            onShare = { onAction(HomeAction.ShareRequested(clip)) },
            onRename = { onAction(HomeAction.RenameRequested(clip)) },
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

    uiState.clipPendingRename?.let { clip ->
        RenameClipDialog(
            clip = clip,
            onConfirm = { onAction(HomeAction.RenameConfirmed(it)) },
            onDismiss = { onAction(HomeAction.RenameDismissed) },
        )
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
 * Only offers a filter when the library actually contains clips it would match.
 *
 * The currently selected filter is always kept, so the chip the user is standing on cannot
 * vanish from under them when its last matching clip is deleted.
 */
private fun HomeUiState.availableFilters(): List<ClipFilter> = buildList {
    add(ClipFilter.All)
    if (filter == ClipFilter.New || clips.any { it.isNew }) add(ClipFilter.New)
    if (filter == ClipFilter.Downloaded || clips.any { it.origin is ClipOrigin.Remote }) {
        add(ClipFilter.Downloaded)
    }
    if (filter == ClipFilter.MyClips || clips.any { it.origin is ClipOrigin.Local }) {
        add(ClipFilter.MyClips)
    }
}

// --- Platform interop ------------------------------------------------------------------------

private val AUDIO_MIME_TYPES = arrayOf("audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav", "audio/*")

/**
 * Reads a picked document into memory.
 *
 * Returns null instead of throwing: a single unreadable pick should be counted and reported,
 * not abort the whole batch.
 */
private suspend fun Context.readPickedFile(uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
    runCatching {
        // Reject anything oversized before allocating for it. Clips are seconds long, so a
        // multi-hundred-megabyte pick is a mistake, and reading it would risk an OOM.
        val declaredSize = sizeOf(uri)
        if (declaredSize != null && declaredSize > MAX_IMPORT_BYTES) return@runCatching null

        val bytes = contentResolver.openInputStream(uri)?.use { stream ->
            stream.readAtMost(MAX_IMPORT_BYTES)
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

private const val MAX_IMPORT_BYTES = 32L * 1024 * 1024
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
