package com.cellocoach.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScoreLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun lib() = ScoreLibrary(tmp.newFolder("scores"))

    @Test
    fun `save then list and read round-trips`() {
        val lib = lib()
        val bytes = "<score/>".toByteArray()
        val name = lib.save("bwv1007.musicxml", bytes)
        assertEquals("bwv1007.musicxml", name)
        assertEquals(listOf("bwv1007.musicxml"), lib.list())
        assertTrue(lib.exists("bwv1007.musicxml"))
        assertArrayEquals(bytes, lib.read("bwv1007.musicxml"))
    }

    @Test
    fun `read missing returns null`() {
        assertNull(lib().read("nope.musicxml"))
    }

    @Test
    fun `sanitize strips paths and query, adds extension`() {
        val lib = lib()
        assertEquals("score.musicxml", lib.sanitize("https://x.com/a/b/score.musicxml?token=1"))
        assertEquals("noext.musicxml", lib.sanitize("noext"))
        assertEquals("keep.mxl", lib.sanitize("../../keep.mxl"))
    }

    @Test
    fun `delete removes the file`() {
        val lib = lib()
        lib.save("a.mxl", byteArrayOf(1))
        assertTrue(lib.delete("a.mxl"))
        assertFalse(lib.exists("a.mxl"))
    }
}
