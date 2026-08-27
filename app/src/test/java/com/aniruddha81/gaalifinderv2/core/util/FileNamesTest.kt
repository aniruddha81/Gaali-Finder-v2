package com.aniruddha81.gaalifinderv2.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cover the cases the old `dropLast(4)` approach got wrong: names without an extension,
 * names with a longer one, and names containing dots.
 */
class FileNamesTest {

    @Test
    fun `stripExtension removes only the final extension`() {
        assertEquals("hello", FileNames.stripExtension("hello.mp3"))
        assertEquals("my.clip", FileNames.stripExtension("my.clip.wav"))
    }

    @Test
    fun `stripExtension leaves a name without an extension untouched`() {
        assertEquals("noextension", FileNames.stripExtension("noextension"))
    }

    @Test
    fun `withExtensionOf preserves the original extension`() {
        assertEquals("new.wav", FileNames.withExtensionOf("new", "old.wav"))
        assertEquals("new.mp3", FileNames.withExtensionOf("new", "old.mp3"))
    }

    @Test
    fun `withExtensionOf falls back to mp3 when the original had none`() {
        assertEquals("new.mp3", FileNames.withExtensionOf("new", "old"))
    }

    @Test
    fun `isValidDisplayName rejects empty, blank and path-bearing names`() {
        assertFalse(FileNames.isValidDisplayName(""))
        assertFalse(FileNames.isValidDisplayName("   "))
        assertFalse(FileNames.isValidDisplayName("a/b"))
        assertFalse(FileNames.isValidDisplayName("a\\b"))
        assertFalse(FileNames.isValidDisplayName(".."))
    }

    @Test
    fun `isValidDisplayName accepts ordinary names`() {
        assertTrue(FileNames.isValidDisplayName("hello"))
        assertTrue(FileNames.isValidDisplayName("hello-there_1"))
    }

    @Test
    fun `isValidDisplayName rejects a name longer than the filesystem limit`() {
        assertFalse(FileNames.isValidDisplayName("a".repeat(200)))
    }

    @Test
    fun `sanitize replaces characters a file name cannot hold`() {
        assertEquals("a_b_c.mp3", FileNames.sanitize("a/b:c.mp3"))
    }

    @Test
    fun `sanitize gives a blank name a usable fallback`() {
        assertEquals("clip.mp3", FileNames.sanitize("   "))
    }

    @Test
    fun `uniqueFileName returns the original when nothing has taken it`() {
        assertEquals("clip.mp3", FileNames.uniqueFileName("clip.mp3") { false })
    }

    @Test
    fun `uniqueFileName counts up past every taken candidate`() {
        val taken = setOf("clip.mp3", "clip (2).mp3", "clip (3).mp3")
        assertEquals("clip (4).mp3", FileNames.uniqueFileName("clip.mp3") { it in taken })
    }
}
