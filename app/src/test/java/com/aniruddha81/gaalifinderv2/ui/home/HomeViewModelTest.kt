package com.aniruddha81.gaalifinderv2.ui.home

import app.cash.turbine.test
import com.aniruddha81.gaalifinderv2.core.connectivity.ConnectivityMonitor
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.AuthState
import com.aniruddha81.gaalifinderv2.domain.model.ClipFilter
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType
import com.aniruddha81.gaalifinderv2.domain.model.StorageQuota
import com.aniruddha81.gaalifinderv2.domain.repository.StorageUsage
import com.aniruddha81.gaalifinderv2.domain.repository.SyncOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeAudioClipRepository
    private lateinit var auth: FakeAuthRepository
    private lateinit var player: FakeAudioPlayer

    private class FakeConnectivity(private val online: Boolean = true) : ConnectivityMonitor {
        override val isOnline: Flow<Boolean> = flowOf(online)
        override fun isCurrentlyOnline(): Boolean = online
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeAudioClipRepository()
        auth = FakeAuthRepository()
        player = FakeAudioPlayer()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(online: Boolean = true) =
        HomeViewModel(repository, auth, player, FakeConnectivity(online))

    private fun clip(
        id: String,
        name: String,
        isNew: Boolean = false,
        uploaderId: String = FakeAuthRepository.TEST_USER.id,
        myReaction: ReactionType = ReactionType.None,
    ) = AudioClip(
        id = id,
        fileId = "file-$id",
        fileName = "$name.mp3",
        uploaderId = uploaderId,
        uploaderName = "Ada",
        isNew = isNew,
        durationMs = 1_000,
        sizeBytes = 10,
        createdAt = id.hashCode().toLong(),
        myReaction = myReaction,
    )

    @Test
    fun `library emissions clear the initial loading flag`() = runTest(dispatcher) {
        val vm = viewModel()
        assertTrue(vm.uiState.value.isInitialLoading)

        repository.clips.value = listOf(clip("1", "a"))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isInitialLoading)
        assertEquals(1, vm.uiState.value.clips.size)
    }

    @Test
    fun `search narrows the visible clips without touching the total count`() =
        runTest(dispatcher) {
            val vm = viewModel()
            repository.clips.value = listOf(clip("1", "apple"), clip("2", "banana"))
            advanceUntilIdle()

            vm.onAction(HomeAction.SearchQueryChanged("app"))
            advanceUntilIdle()

            assertEquals(listOf("1"), vm.uiState.value.clips.map { it.id })
            assertEquals(2, vm.uiState.value.totalClipCount)
        }

    @Test
    fun `closing search clears the query`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.clips.value = listOf(clip("1", "apple"), clip("2", "banana"))
        advanceUntilIdle()

        vm.onAction(HomeAction.OpenSearch)
        vm.onAction(HomeAction.SearchQueryChanged("app"))
        advanceUntilIdle()
        vm.onAction(HomeAction.CloseSearch)
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.searchQuery)
        assertEquals(2, vm.uiState.value.clips.size)
    }

    // --- Playback --------------------------------------------------------------------------

    @Test
    fun `playing a clip fetches it first and marks a new one as seen`() = runTest(dispatcher) {
        val vm = viewModel()
        val target = clip("1", "a", isNew = true)
        repository.clips.value = listOf(target)
        advanceUntilIdle()

        vm.onAction(HomeAction.TogglePlayback(target))
        advanceUntilIdle()

        assertEquals(listOf("1"), player.played)
        assertEquals(listOf("/clips/file-1.mp3"), player.paths)
        assertEquals(listOf("1"), repository.markSeenCalls)
    }

    @Test
    fun `a failed download surfaces an error and never reaches the player`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val target = clip("1", "a", isNew = true)
            repository.clips.value = listOf(target)
            repository.playableResult = DataResult.Failure(AppError.NoConnection)
            advanceUntilIdle()

            vm.effects.test {
                vm.onAction(HomeAction.TogglePlayback(target))
                advanceUntilIdle()

                val effect = awaitItem() as HomeEffect.ShowMessage
                assertEquals(AppError.NoConnection, (effect.message as UiMessage.FromError).error)
                cancelAndIgnoreRemainingEvents()
            }

            assertTrue(player.played.isEmpty())
            assertTrue(repository.markSeenCalls.isEmpty())
        }

    @Test
    fun `a playback failure does not mark the clip seen`() = runTest(dispatcher) {
        val vm = viewModel()
        val target = clip("1", "a", isNew = true)
        repository.clips.value = listOf(target)
        advanceUntilIdle()

        player.failWith(AppError.ClipFileMissing)

        vm.effects.test {
            vm.onAction(HomeAction.TogglePlayback(target))
            advanceUntilIdle()

            val effect = awaitItem() as HomeEffect.ShowMessage
            assertEquals(AppError.ClipFileMissing, (effect.message as UiMessage.FromError).error)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(repository.markSeenCalls.isEmpty())
    }

    @Test
    fun `tapping the playing clip stops it without downloading again`() = runTest(dispatcher) {
        val vm = viewModel()
        val target = clip("1", "a")
        repository.clips.value = listOf(target)
        advanceUntilIdle()

        vm.onAction(HomeAction.TogglePlayback(target))
        advanceUntilIdle()
        vm.onAction(HomeAction.TogglePlayback(target))
        advanceUntilIdle()

        assertEquals(1, player.played.size)
        assertTrue(player.stopCount > 0)
    }

    // --- Sync ------------------------------------------------------------------------------

    @Test
    fun `a start-up sync failure stays silent`() = runTest(dispatcher) {
        repository.syncResult = DataResult.Failure(AppError.NoConnection)
        val vm = viewModel(online = false)

        vm.effects.test {
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a user-initiated refresh reports its failure`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        repository.syncResult = DataResult.Failure(AppError.NoConnection)

        vm.effects.test {
            vm.onAction(HomeAction.Refresh)
            advanceUntilIdle()

            val effect = awaitItem() as HomeEffect.ShowMessage
            assertEquals(AppError.NoConnection, (effect.message as UiMessage.FromError).error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a refresh is ignored while one is already running`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val afterStartup = repository.syncCount

        vm.onAction(HomeAction.Refresh)
        vm.onAction(HomeAction.Refresh)
        advanceUntilIdle()

        assertEquals(afterStartup + 1, repository.syncCount)
    }

    /**
     * The real [AuthRepository] starts at [AuthState.Unknown] and probes the session in the
     * background, so the start-up sync in `init` runs before anyone knows who is signed in. The
     * catalogue it fetches therefore carries no reactions, and the session landing afterwards has
     * to trigger a second pass — otherwise a signed-in user sees none of their likes until they
     * pull to refresh by hand.
     */
    @Test
    fun `a session arriving after the start-up sync triggers another one`() = runTest(dispatcher) {
        auth = FakeAuthRepository(AuthState.Unknown)
        val vm = viewModel()
        advanceUntilIdle()
        val afterStartup = repository.syncCount

        auth.setSignedIn()
        advanceUntilIdle()

        assertEquals(afterStartup + 1, repository.syncCount)
    }

    /** The same must hold when the session resolves while the start-up sync is still running. */
    @Test
    fun `a session resolving mid-sync is not dropped`() = runTest(dispatcher) {
        auth = FakeAuthRepository(AuthState.Unknown)
        val vm = viewModel()
        // Deliberately not idle: the start-up sync is still in flight here, which is exactly the
        // window where the old code discarded the session change and never re-read the catalogue.
        auth.setSignedIn()
        advanceUntilIdle()

        assertTrue(repository.syncCount >= 2)
    }

    @Test
    fun `a successful refresh reports how many clips arrived`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        repository.syncResult = DataResult.Success(SyncOutcome(total = 10, added = 3))

        vm.effects.test {
            vm.onAction(HomeAction.Refresh)
            advanceUntilIdle()

            val effect = awaitItem() as HomeEffect.ShowMessage
            assertEquals(3, (effect.message as UiMessage.Plural).count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Auth gating -----------------------------------------------------------------------

    @Test
    fun `the plus button sends a guest to sign-in, then straight on to the picker`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.effects.test {
                vm.onAction(HomeAction.UploadRequested)
                advanceUntilIdle()

                val effect = awaitItem() as HomeEffect.RequestSignIn
                assertTrue(effect.thenOpenPicker)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the plus button opens the picker directly for a signed-in user`() = runTest(dispatcher) {
        auth.setSignedIn()
        val vm = viewModel()
        advanceUntilIdle()

        vm.effects.test {
            vm.onAction(HomeAction.UploadRequested)
            advanceUntilIdle()

            assertEquals(HomeEffect.OpenFilePicker, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reacting as a guest asks for sign-in instead of failing silently`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val target = clip("1", "a")
            repository.clips.value = listOf(target)
            advanceUntilIdle()

            vm.effects.test {
                vm.onAction(HomeAction.ReactionTapped(target, ReactionType.Like))
                advanceUntilIdle()

                val effect = awaitItem() as HomeEffect.RequestSignIn
                // Reacting must not drag them into the file picker afterwards.
                assertFalse(effect.thenOpenPicker)
                cancelAndIgnoreRemainingEvents()
            }

            assertTrue(repository.reactions.isEmpty())
        }

    @Test
    fun `a signed-in user's reaction reaches the repository`() = runTest(dispatcher) {
        auth.setSignedIn()
        val vm = viewModel()
        val target = clip("1", "a")
        repository.clips.value = listOf(target)
        advanceUntilIdle()

        vm.onAction(HomeAction.ReactionTapped(target, ReactionType.Dislike))
        advanceUntilIdle()

        assertEquals(listOf("1" to ReactionType.Dislike), repository.reactions)
    }

    @Test
    fun `signing in re-syncs so the new account's reactions are loaded`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val afterStartup = repository.syncCount

        auth.setSignedIn()
        advanceUntilIdle()

        assertTrue(repository.syncCount > afterStartup)
        assertTrue(vm.uiState.value.isSignedIn)
    }

    // --- Upload and quota -------------------------------------------------------------------

    @Test
    fun `uploading passes the signed-in user through as the uploader`() = runTest(dispatcher) {
        auth.setSignedIn()
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAction(HomeAction.UploadFiles(listOf(PickedFile("a.mp3", ByteArray(10)))))
        advanceUntilIdle()

        assertEquals(1, repository.uploadRequests.size)
        assertEquals("u1", repository.uploadRequests.first().uploaderId)
        assertEquals("Ada", repository.uploadRequests.first().uploaderName)
        assertFalse(vm.uiState.value.isUploading)
    }

    @Test
    fun `an oversized file is reported by name and does not stop the rest of the batch`() =
        runTest(dispatcher) {
            auth.setSignedIn()
            val vm = viewModel()
            advanceUntilIdle()

            repository.uploadResult =
                DataResult.Failure(AppError.FileTooLarge(300_000))

            vm.onAction(
                HomeAction.UploadFiles(
                    listOf(PickedFile("big.mp3", ByteArray(10)), PickedFile("also.mp3", ByteArray(10)))
                )
            )
            advanceUntilIdle()

            // Both were attempted: a per-file rejection is not a reason to abandon the batch.
            assertEquals(2, repository.uploadRequests.size)
            assertNull(vm.uiState.value.quotaBlock)
        }

    @Test
    fun `hitting the quota raises the upgrade prompt and abandons the rest of the batch`() =
        runTest(dispatcher) {
            auth.setSignedIn()
            val vm = viewModel()
            advanceUntilIdle()

            repository.uploadResult = DataResult.Failure(
                AppError.QuotaExceeded(
                    usedBytes = StorageQuota.FREE_TOTAL_BYTES,
                    limitBytes = StorageQuota.FREE_TOTAL_BYTES,
                    isPremium = false,
                )
            )

            vm.onAction(
                HomeAction.UploadFiles(
                    listOf(PickedFile("a.mp3", ByteArray(10)), PickedFile("b.mp3", ByteArray(10)))
                )
            )
            advanceUntilIdle()

            // The second file could not have fitted either, so it is never attempted.
            assertEquals(1, repository.uploadRequests.size)
            assertNotNull(vm.uiState.value.quotaBlock)
        }

    @Test
    fun `the upgrade button swaps the quota dialog for the placeholder screen`() =
        runTest(dispatcher) {
            auth.setSignedIn()
            val vm = viewModel()
            advanceUntilIdle()

            repository.uploadResult = DataResult.Failure(
                AppError.QuotaExceeded(0, StorageQuota.FREE_TOTAL_BYTES, isPremium = false)
            )
            vm.onAction(HomeAction.UploadFiles(listOf(PickedFile("a.mp3", ByteArray(10)))))
            advanceUntilIdle()

            vm.onAction(HomeAction.UpgradeRequested)

            assertNull(vm.uiState.value.quotaBlock)
            assertTrue(vm.uiState.value.isUpgradeScreenOpen)
        }

    @Test
    fun `uploading while signed out is refused rather than sent with an empty uploader`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.effects.test {
                vm.onAction(HomeAction.UploadFiles(listOf(PickedFile("a.mp3", ByteArray(10)))))
                advanceUntilIdle()

                val effect = awaitItem() as HomeEffect.ShowMessage
                assertEquals(AppError.NotSignedIn, (effect.message as UiMessage.FromError).error)
                cancelAndIgnoreRemainingEvents()
            }

            assertTrue(repository.uploadRequests.isEmpty())
        }

    @Test
    fun `storage usage is loaded for a signed-in user and cleared on sign-out`() =
        runTest(dispatcher) {
            repository.usageResult = DataResult.Success(
                StorageUsage(usedBytes = 500, limitBytes = StorageQuota.FREE_TOTAL_BYTES, isPremium = false)
            )
            auth.setSignedIn()
            val vm = viewModel()
            advanceUntilIdle()

            assertEquals(500L, vm.uiState.value.storageUsage?.usedBytes)

            auth.setGuest()
            advanceUntilIdle()

            assertNull(vm.uiState.value.storageUsage)
        }

    // --- Delete ----------------------------------------------------------------------------

    @Test
    fun `deleting stops playback of the clip being removed`() = runTest(dispatcher) {
        auth.setSignedIn()
        val vm = viewModel()
        val target = clip("1", "a")
        repository.clips.value = listOf(target)
        advanceUntilIdle()

        vm.onAction(HomeAction.TogglePlayback(target))
        advanceUntilIdle()

        vm.onAction(HomeAction.DeleteRequested(target))
        vm.onAction(HomeAction.DeleteConfirmed)
        advanceUntilIdle()

        assertTrue(player.stopCount > 0)
        assertNull(vm.uiState.value.clipPendingDelete)
    }

    @Test
    fun `filtering to New shows only unheard clips`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.clips.value = listOf(clip("1", "a", isNew = true), clip("2", "b"))
        advanceUntilIdle()

        vm.onAction(HomeAction.FilterChanged(ClipFilter.New))
        advanceUntilIdle()

        assertEquals(listOf("1"), vm.uiState.value.clips.map { it.id })
        assertEquals(2, vm.uiState.value.totalClipCount)
    }

    @Test
    fun `the state reflects the session so the UI can gate on it`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(AuthState.Guest, vm.uiState.value.authState)
        assertFalse(vm.uiState.value.canUpload)

        auth.setSignedIn()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isSignedIn)
        assertTrue(vm.uiState.value.canUpload)
        assertEquals("u1", vm.uiState.value.currentUserId)
    }
}
