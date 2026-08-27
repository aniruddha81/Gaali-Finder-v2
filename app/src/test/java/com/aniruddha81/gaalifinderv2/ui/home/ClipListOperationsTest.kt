package com.aniruddha81.gaalifinderv2.ui.home

import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.ClipFilter
import com.aniruddha81.gaalifinderv2.domain.model.ClipSort
import org.junit.Assert.assertEquals
import org.junit.Test

/** The search/filter/sort pipeline that decides what the grid shows. */
class ClipListOperationsTest {

    private val me = "me"

    private fun clip(
        id: String,
        name: String,
        uploaderId: String = me,
        uploaderName: String = "Ada",
        isNew: Boolean = false,
        durationMs: Long = 1_000,
        createdAt: Long = 0,
        likeCount: Int = 0,
        dislikeCount: Int = 0,
        cachedPath: String? = null,
    ) = AudioClip(
        id = id,
        fileId = "file-$id",
        fileName = "$name.mp3",
        uploaderId = uploaderId,
        uploaderName = uploaderName,
        isNew = isNew,
        durationMs = durationMs,
        sizeBytes = 100,
        createdAt = createdAt,
        likeCount = likeCount,
        dislikeCount = dislikeCount,
        cachedPath = cachedPath,
    )

    private val library = listOf(
        clip("1", "banana", isNew = true, durationMs = 3_000, createdAt = 300, likeCount = 1),
        clip(
            "2", "Apple",
            uploaderId = "other", uploaderName = "Grace",
            durationMs = 1_000, createdAt = 100, likeCount = 10, dislikeCount = 1,
            cachedPath = "/clips/file-2.mp3",
        ),
        clip(
            "3", "cherry",
            uploaderId = "other", uploaderName = "Grace",
            isNew = true, durationMs = 2_000, createdAt = 200, dislikeCount = 4,
            cachedPath = "/clips/file-3.mp3",
        ),
    )

    @Test
    fun `filter All keeps everything`() {
        assertEquals(3, library.applyFilter(ClipFilter.All, me).size)
    }

    @Test
    fun `filter New keeps only unheard clips`() {
        assertEquals(listOf("1", "3"), library.applyFilter(ClipFilter.New, me).map { it.id })
    }

    @Test
    fun `filter Downloaded keeps only clips cached on this device`() {
        assertEquals(listOf("2", "3"), library.applyFilter(ClipFilter.Downloaded, me).map { it.id })
    }

    @Test
    fun `filter MyClips keeps only clips this user uploaded`() {
        assertEquals(listOf("1"), library.applyFilter(ClipFilter.MyClips, me).map { it.id })
    }

    @Test
    fun `a guest has no uploads, so MyClips is empty rather than showing everyone's`() {
        assertEquals(emptyList<String>(), library.applyFilter(ClipFilter.MyClips, null).map { it.id })
    }

    @Test
    fun `search is case-insensitive and matches a substring`() {
        assertEquals(listOf("2"), library.applySearch("appl").map { it.id })
        assertEquals(listOf("2"), library.applySearch("APPLE").map { it.id })
    }

    @Test
    fun `search also matches the uploader, so you can find someone's clips`() {
        assertEquals(listOf("2", "3"), library.applySearch("grace").map { it.id })
    }

    @Test
    fun `search matches the display name, not the extension`() {
        // "mp3" is in every file name but no display name, so it must match nothing.
        assertEquals(emptyList<String>(), library.applySearch("mp3").map { it.id })
    }

    @Test
    fun `a blank or whitespace-only query returns the whole list`() {
        assertEquals(3, library.applySearch("").size)
        assertEquals(3, library.applySearch("   ").size)
    }

    @Test
    fun `sort by name ignores case`() {
        assertEquals(listOf("2", "1", "3"), library.applySort(ClipSort.NameAsc).map { it.id })
    }

    @Test
    fun `sort by recent puts the newest first`() {
        assertEquals(listOf("1", "3", "2"), library.applySort(ClipSort.RecentFirst).map { it.id })
    }

    @Test
    fun `sort by longest puts the longest first`() {
        assertEquals(listOf("1", "3", "2"), library.applySort(ClipSort.LongestFirst).map { it.id })
    }

    @Test
    fun `sort by popular ranks on net score, so a divisive clip falls below a liked one`() {
        // Scores: "2" is 10-1=9, "1" is 1, "3" is 0-4=-4.
        assertEquals(listOf("2", "1", "3"), library.applySort(ClipSort.MostPopular).map { it.id })
    }

    @Test
    fun `equal net scores fall back to recency for a stable order`() {
        val tied = listOf(
            clip("a", "a", createdAt = 100, likeCount = 2, dislikeCount = 1),
            clip("b", "b", createdAt = 200, likeCount = 5, dislikeCount = 4),
        )
        assertEquals(listOf("b", "a"), tied.applySort(ClipSort.MostPopular).map { it.id })
    }

    @Test
    fun `filter and search compose`() {
        val result = library.applyFilter(ClipFilter.New, me).applySearch("err")
        assertEquals(listOf("3"), result.map { it.id })
    }

    @Test
    fun `kilobyte conversion rounds up, so one byte over never reads as the limit`() {
        assertEquals(200L, 204_800L.toKilobytes())
        assertEquals(201L, 204_801L.toKilobytes())
    }
}
