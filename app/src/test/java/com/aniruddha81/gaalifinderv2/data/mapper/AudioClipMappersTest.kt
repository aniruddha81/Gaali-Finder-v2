package com.aniruddha81.gaalifinderv2.data.mapper

import com.aniruddha81.gaalifinderv2.data.local.entity.AudioFileEntity
import com.aniruddha81.gaalifinderv2.data.remote.RemoteAudioClip
import com.aniruddha81.gaalifinderv2.domain.model.ReactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks in the translation between the cached row, the wire model and the domain model. */
class AudioClipMappersTest {

    @Test
    fun `entity maps onto the domain model with the extension stripped for display`() {
        val entity = AudioFileEntity(
            documentId = "doc5",
            fileId = "file5",
            fileName = "big laugh.mp3",
            uploaderId = "u1",
            uploaderName = "Ada",
            isNew = true,
            durationMs = 1_500,
            sizeBytes = 2_048,
            createdAt = 99,
            likeCount = 4,
            dislikeCount = 1,
            myReaction = "like",
            cachedPath = "/data/clips/file5.mp3",
        )

        val clip = entity.toDomain()

        assertEquals("doc5", clip.id)
        assertEquals("file5", clip.fileId)
        assertEquals("big laugh", clip.displayName)
        assertEquals("big laugh.mp3", clip.fileName)
        assertEquals("Ada", clip.uploaderName)
        assertEquals(ReactionType.Like, clip.myReaction)
        assertTrue(clip.hasKnownDuration)
        assertTrue(clip.isDownloaded)
    }

    @Test
    fun `net score is likes minus dislikes and may go negative`() {
        val entity = AudioFileEntity(documentId = "d", likeCount = 2, dislikeCount = 5)
        assertEquals(-3, entity.toDomain().netScore)
    }

    @Test
    fun `only the uploader may delete a clip`() {
        val clip = AudioFileEntity(documentId = "d", uploaderId = "owner").toDomain()

        assertTrue(clip.isDeletableBy("owner"))
        assertFalse(clip.isDeletableBy("someone-else"))
        // A guest has no id, and must never pass an ownership check.
        assertFalse(clip.isDeletableBy(null))
    }

    @Test
    fun `a clip with no cached copy is not downloaded`() {
        val entity = AudioFileEntity(documentId = "d", cachedPath = null)
        assertFalse(entity.toDomain().isDownloaded)
    }

    @Test
    fun `a clip with no probed duration reports it as unknown`() {
        val entity = AudioFileEntity(documentId = "d", durationMs = 0)
        assertFalse(entity.toDomain().hasKnownDuration)
    }

    @Test
    fun `an unrecognised reaction value degrades to no reaction`() {
        val entity = AudioFileEntity(documentId = "d", myReaction = "shrug")
        assertEquals(ReactionType.None, entity.toDomain().myReaction)
    }

    @Test
    fun `a remote clip becomes a cache row carrying the caller's reaction`() {
        val remote = RemoteAudioClip(
            documentId = "doc1",
            fileId = "file1",
            fileName = "oi.mp3",
            uploaderId = "u1",
            uploaderName = "Ada",
            sizeBytes = 1_024,
            createdAt = 42,
            likeCount = 3,
            dislikeCount = 0,
        )

        val entity = remote.toEntity(ReactionType.Dislike)

        assertEquals("doc1", entity.documentId)
        assertEquals("dislike", entity.myReaction)
        assertEquals(3, entity.likeCount)
        // A freshly fetched row has never been downloaded; the merge re-attaches any cached copy.
        assertNull(entity.cachedPath)
    }

    @Test
    fun `no reaction is stored as null rather than a none marker`() {
        val remote = RemoteAudioClip("d", "f", "a.mp3", "u", "Ada", 1, 1, 0, 0)
        assertNull(remote.toEntity(ReactionType.None).myReaction)
    }
}
