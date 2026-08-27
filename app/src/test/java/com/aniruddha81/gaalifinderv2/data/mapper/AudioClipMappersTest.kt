package com.aniruddha81.gaalifinderv2.data.mapper

import com.aniruddha81.gaalifinderv2.data.local.entity.AudioFileEntity
import com.aniruddha81.gaalifinderv2.domain.model.ClipOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks in the legacy `source` column convention so a future change cannot break old rows. */
class AudioClipMappersTest {

    @Test
    fun `the literal local marker maps to a local origin`() {
        assertEquals(ClipOrigin.Local, "local".toClipOrigin())
    }

    @Test
    fun `a blank source maps to local rather than an empty remote id`() {
        assertEquals(ClipOrigin.Local, "".toClipOrigin())
    }

    @Test
    fun `any other value is treated as an Appwrite file id`() {
        assertEquals(ClipOrigin.Remote("abc123"), "abc123".toClipOrigin())
    }

    @Test
    fun `origin survives a round trip through the source column`() {
        assertEquals("local", ClipOrigin.Local.toSourceColumn())
        assertEquals("xyz", ClipOrigin.Remote("xyz").toSourceColumn())
    }

    @Test
    fun `entity maps onto the domain model with the extension stripped for display`() {
        val entity = AudioFileEntity(
            id = 5,
            fileName = "big laugh.mp3",
            path = "/data/clips/big laugh.mp3",
            source = "local",
            isNew = true,
            durationMs = 1_500,
            sizeBytes = 2_048,
            addedAt = 99,
        )

        val clip = entity.toDomain()

        assertEquals(5L, clip.id)
        assertEquals("big laugh", clip.displayName)
        assertEquals("big laugh.mp3", clip.fileName)
        assertEquals(ClipOrigin.Local, clip.origin)
        assertTrue(clip.isDeletable)
        assertTrue(clip.hasKnownDuration)
    }

    @Test
    fun `a downloaded clip is not deletable`() {
        val entity = AudioFileEntity(id = 1, fileName = "a.mp3", path = "/a.mp3", source = "remote1")
        assertFalse(entity.toDomain().isDeletable)
    }

    @Test
    fun `a clip with no probed duration reports it as unknown`() {
        val entity = AudioFileEntity(id = 1, fileName = "a.mp3", path = "/a.mp3", durationMs = 0)
        assertFalse(entity.toDomain().hasKnownDuration)
    }
}
