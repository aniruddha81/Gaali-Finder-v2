package com.aniruddha81.gaalifinderv2.ui.home

import app.cash.turbine.test
import com.aniruddha81.gaalifinderv2.core.connectivity.ConnectivityMonitor
import com.aniruddha81.gaalifinderv2.core.error.AppError
import com.aniruddha81.gaalifinderv2.core.result.DataResult
import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.ClipFilter
import com.aniruddha81.gaalifinderv2.domain.model.ClipOrigin
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeAudioClipRepository
    private lateinit var player: FakeAudioPlayer

    private class FakeConnectivity(private val online: Boolean = true) : ConnectivityMonitor {
        override val isOnline: Flow<Boolean> = flowOf(online)
        override fun isCurrentlyOnline(): Boolean = online
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeAudioClipRepository()
        player = FakeAudioPlayer()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(online: Boolean = true) =
        HomeViewModel(repository, player, FakeConnectivity(online))

    private fun clip(id: Long, name: String, isNew: Boolean = false) = AudioClip(
        id = id,
        fileName = "$name.mp3",
        filePath = "/clips/$name.mp3",
        origin = ClipOrigin.Local,
        isNew = isNew,
        durationMs = 1_000,
        sizeBytes = 10,
        addedAt = id,
    )

    @Test
    fun `library emissions clear the initial loading flag`() = runTest(dispatcher) {
        val vm = viewModel()
        assertTrue(vm.uiState.value.isInitialLoading)

        repository.clips.value = listOf(clip(1, "a"))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isInitialLoading)
        assertEquals(1, vm.uiState.value.clips.size)
    }

    @Test
    fun `search narrows the visible clips without touching the total count`() =
        runTest(dispatcher) {
            val vm = viewModel()
            repository.clips.value = listOf(clip(1, "apple"), clip(2, "banana"))
            advanceUntilIdle()

            vm.onAction(HomeAction.SearchQueryChanged("app"))
            advanceUntilIdle()

            assertEquals(listOf(1L), vm.uiState.value.clips.map { it.id })
        }

    @Test
    fun `closing search clears the query`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.clips.value = listOf(clip(1, "apple"), clip(2, "banana"))
        advanceUntilIdle()

        vm.onAction(HomeAction.OpenSearch)
        vm.onAction(HomeAction.SearchQueryChanged("app"))
        advanceUntilIdle()
        vm.onAction(HomeAction.CloseSearch)
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.searchQuery)
        assertEquals(2, vm.uiState.value.clips.size)
    }

    @Test
    fun `playing a new clip marks it as seen`() = runTest(dispatcher) {
        val vm = viewModel()
        val target = clip(1, "a", isNew = true)
        repository.clips.value = listOf(target)
        advanceUntilIdle()

        vm.onAction(HomeAction.TogglePlayback(target))
        advanceUntilIdle()

        assertEquals(listOf(1L), repository.markSeenCalls)
    }

    @Test
    fun `a playback failure surfaces an error and does not mark the clip seen`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val target = clip(1, "a", isNew = true)
            repository.clips.value = listOf(target)
            advanceUntilIdle()

            player.failWith(AppError.ClipFileMissing)

            vm.effects.test {
                vm.onAction(HomeAction.TogglePlayback(target))
                advanceUntilIdle()

                val effect = awaitItem()
                assertTrue(effect is HomeEffect.ShowMessage)
                assertEquals(
                    AppError.ClipFileMissing,
                    ((effect as HomeEffect.ShowMessage).message as UiMessage.FromError).error,
                )
                cancelAndIgnoreRemainingEvents()
            }

            assertTrue(repository.markSeenCalls.isEmpty())
        }

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

    @Test
    fun `a successful refresh reports how many clips arrived`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        repository.syncResult = DataResult.Success(SyncOutcome(downloaded = 3, alreadyPresent = 1, failed = 0))

        vm.effects.test {
            vm.onAction(HomeAction.Refresh)
            advanceUntilIdle()

            val effect = awaitItem() as HomeEffect.ShowMessage
            assertTrue(effect.message is UiMessage.Plural)
            assertEquals(3, (effect.message as UiMessage.Plural).count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting stops playback of the clip being removed`() = runTest(dispatcher) {
        val vm = viewModel()
        val target = clip(1, "a")
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
    fun `a failed rename keeps the dialog open so the user can correct it`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val target = clip(1, "a")
            repository.clips.value = listOf(target)
            advanceUntilIdle()

            repository.renameResult = DataResult.Failure(AppError.DuplicateName)

            vm.onAction(HomeAction.RenameRequested(target))
            vm.onAction(HomeAction.RenameConfirmed("taken"))
            advanceUntilIdle()

            assertEquals(target, vm.uiState.value.clipPendingRename)
        }

    @Test
    fun `a successful rename closes the dialog`() = runTest(dispatcher) {
        val vm = viewModel()
        val target = clip(1, "a")
        repository.clips.value = listOf(target)
        advanceUntilIdle()

        repository.renameResult = DataResult.Success(target.copy(fileName = "b.mp3"))

        vm.onAction(HomeAction.RenameRequested(target))
        vm.onAction(HomeAction.RenameConfirmed("b"))
        advanceUntilIdle()

        assertNull(vm.uiState.value.clipPendingRename)
    }

    @Test
    fun `filtering to New shows only unheard clips`() = runTest(dispatcher) {
        val vm = viewModel()
        repository.clips.value = listOf(clip(1, "a", isNew = true), clip(2, "b"))
        advanceUntilIdle()

        vm.onAction(HomeAction.FilterChanged(ClipFilter.New))
        advanceUntilIdle()

        assertEquals(listOf(1L), vm.uiState.value.clips.map { it.id })
        assertEquals(2, vm.uiState.value.totalClipCount)
    }
}
