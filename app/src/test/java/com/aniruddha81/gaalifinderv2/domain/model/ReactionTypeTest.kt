package com.aniruddha81.gaalifinderv2.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The exact vote rules: mutually exclusive, freely toggleable.
 *
 * These are worth pinning down precisely because they are easy to get subtly wrong — switching
 * from dislike to like has to be one action, not a clear followed by a set.
 */
class ReactionTypeTest {

    @Test
    fun `tapping like from none applies a like`() {
        assertEquals(ReactionType.Like, ReactionType.None.toggledBy(ReactionType.Like))
    }

    @Test
    fun `tapping like again removes it`() {
        assertEquals(ReactionType.None, ReactionType.Like.toggledBy(ReactionType.Like))
    }

    @Test
    fun `tapping like while disliking switches straight over`() {
        assertEquals(ReactionType.Like, ReactionType.Dislike.toggledBy(ReactionType.Like))
    }

    @Test
    fun `tapping dislike from none applies a dislike`() {
        assertEquals(ReactionType.Dislike, ReactionType.None.toggledBy(ReactionType.Dislike))
    }

    @Test
    fun `tapping dislike again removes it`() {
        assertEquals(ReactionType.None, ReactionType.Dislike.toggledBy(ReactionType.Dislike))
    }

    @Test
    fun `tapping dislike while liking switches straight over`() {
        assertEquals(ReactionType.Dislike, ReactionType.Like.toggledBy(ReactionType.Dislike))
    }

    @Test
    fun `a reaction can be changed and removed any number of times`() {
        var state = ReactionType.None
        state = state.toggledBy(ReactionType.Like)
        state = state.toggledBy(ReactionType.Dislike)
        state = state.toggledBy(ReactionType.Dislike)
        state = state.toggledBy(ReactionType.Like)

        assertEquals(ReactionType.Like, state)
    }

    @Test
    fun `none has no wire value, since it is stored as the absence of a document`() {
        assertNull(ReactionType.None.wireValue)
        assertEquals("like", ReactionType.Like.wireValue)
        assertEquals("dislike", ReactionType.Dislike.wireValue)
    }

    @Test
    fun `wire values round-trip and unknown input degrades to none`() {
        assertEquals(ReactionType.Like, ReactionType.fromWire("like"))
        assertEquals(ReactionType.Dislike, ReactionType.fromWire("DISLIKE"))
        assertEquals(ReactionType.None, ReactionType.fromWire(null))
        assertEquals(ReactionType.None, ReactionType.fromWire("maybe"))
    }
}
