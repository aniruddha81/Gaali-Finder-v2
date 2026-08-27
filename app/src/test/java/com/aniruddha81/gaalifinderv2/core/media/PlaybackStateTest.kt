package com.aniruddha81.gaalifinderv2.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateTest {

    @Test
    fun `idle state reports nothing playing`() {
        val state = PlaybackState.Idle
        assertFalse(state.isActive)
        assertFalse(state.isPlaying("c1"))
    }

    @Test
    fun `only the active clip reports as playing`() {
        val state = PlaybackState(clipId = "c7", status = PlaybackState.Status.Playing)
        assertTrue(state.isPlaying("c7"))
        assertFalse(state.isPlaying("c8"))
    }

    @Test
    fun `a preparing clip is active but not yet playing`() {
        val state = PlaybackState(clipId = "c7", status = PlaybackState.Status.Preparing)
        assertTrue(state.isActive)
        assertTrue(state.isPreparing("c7"))
        assertFalse(state.isPlaying("c7"))
    }

    @Test
    fun `progress is the fraction of the duration elapsed`() {
        val state = PlaybackState(
            clipId = "c1",
            status = PlaybackState.Status.Playing,
            positionMs = 500,
            durationMs = 2_000,
        )
        assertEquals(0.25f, state.progressFor("c1"), 0.001f)
    }

    @Test
    fun `progress is zero for a clip that is not the active one`() {
        val state = PlaybackState(
            clipId = "c1",
            status = PlaybackState.Status.Playing,
            positionMs = 500,
            durationMs = 2_000,
        )
        assertEquals(0f, state.progressFor("c2"), 0.001f)
    }

    @Test
    fun `an unknown duration yields zero progress rather than dividing by zero`() {
        val state = PlaybackState(
            clipId = "c1",
            status = PlaybackState.Status.Playing,
            positionMs = 500,
            durationMs = 0,
        )
        assertEquals(0f, state.progressFor("c1"), 0.001f)
    }

    @Test
    fun `progress is clamped when position overshoots duration`() {
        val state = PlaybackState(
            clipId = "c1",
            status = PlaybackState.Status.Playing,
            positionMs = 5_000,
            durationMs = 2_000,
        )
        assertEquals(1f, state.progressFor("c1"), 0.001f)
    }
}
