package com.aniruddha81.gaalifinderv2.ui.home

import com.aniruddha81.gaalifinderv2.domain.model.AudioClip
import com.aniruddha81.gaalifinderv2.domain.model.ClipFilter
import com.aniruddha81.gaalifinderv2.domain.model.ClipOrigin
import com.aniruddha81.gaalifinderv2.domain.model.ClipSort
import org.junit.Assert.assertEquals
import org.junit.Test

/** The search/filter/sort pipeline that decides what the grid shows. */
class ClipListOperationsTest {

    private fun clip(
        id: Long,
        name: String,
        origin: ClipOrigin = ClipOrigin.Local,
        isNew: Boolean = false,
        durationMs: Long = 1_000,
        addedAt: Long = 0,
    ) = AudioClip(
        id = id,
        fileName = "$name.mp3",
        filePath = "/clips/$name.mp3",
        origin = origin,
        isNew = isNew,
        durationMs = durationMs,
        sizeBytes = 100,
        addedAt = addedAt,
    )

    private val library = listOf(
        clip(1, "banana", isNew = true, durationMs = 3_000, addedAt = 300),
        clip(2, "Apple", origin = ClipOrigin.Remote("r1"), durationMs = 1_000, addedAt = 100),
        clip(3, "cherry", origin = ClipOrigin.Remote("r2"), isNew = true, durationMs = 2_000, addedAt = 200),
    )

    @Test
    fun `filter All keeps everything`() {
        assertEquals(3, library.applyFilter(ClipFilter.All).size)
    }

    @Test
    fun `filter New keeps only unheard clips`() {
        assertEquals(listOf(1L, 3L), library.applyFilter(ClipFilter.New).map { it.id })
    }

    @Test
    fun `filter Downloaded keeps only remote clips`() {
        assertEquals(listOf(2L, 3L), library.applyFilter(ClipFilter.Downloaded).map { it.id })
    }

    @Test
    fun `filter MyClips keeps only imported clips`() {
        assertEquals(listOf(1L), library.applyFilter(ClipFilter.MyClips).map { it.id })
    }

    @Test
    fun `search is case-insensitive and matches a substring`() {
        assertEquals(listOf(2L), library.applySearch("appl").map { it.id })
        assertEquals(listOf(2L), library.applySearch("APPLE").map { it.id })
    }

    @Test
    fun `search matches the display name, not the extension`() {
        // "mp3" is in every file name but no display name, so it must match nothing.
        assertEquals(emptyList<Long>(), library.applySearch("mp3").map { it.id })
    }

    @Test
    fun `a blank or whitespace-only query returns the whole list`() {
        assertEquals(3, library.applySearch("").size)
        assertEquals(3, library.applySearch("   ").size)
    }

    @Test
    fun `sort by name ignores case`() {
        assertEquals(
            listOf(2L, 1L, 3L),
            library.applySort(ClipSort.NameAsc).map { it.id },
        )
    }

    @Test
    fun `sort by recent puts the newest first`() {
        assertEquals(listOf(1L, 3L, 2L), library.applySort(ClipSort.RecentFirst).map { it.id })
    }

    @Test
    fun `sort by longest puts the longest first`() {
        assertEquals(listOf(1L, 3L, 2L), library.applySort(ClipSort.LongestFirst).map { it.id })
    }

    @Test
    fun `filter and search compose`() {
        val result = library.applyFilter(ClipFilter.New).applySearch("err")
        assertEquals(listOf(3L), result.map { it.id })
    }
}
