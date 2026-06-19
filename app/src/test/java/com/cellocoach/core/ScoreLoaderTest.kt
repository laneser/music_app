package com.cellocoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM unit tests for [ScoreLoader].
 *
 * These run on the host JVM (no Android framework), so the bundled asset is read
 * straight off disk via [File] rather than through an `AssetManager`.
 */
class ScoreLoaderTest {

    /** Locate `g_major_scale.musicxml` whether tests run from the repo root or the app module. */
    private fun assetBytes(name: String): ByteArray {
        val candidates = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name"),
            File("../app/src/main/assets/$name"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Asset $name not found; cwd=${File(".").absolutePath}")
        return file.readBytes()
    }

    @Test
    fun loadsBundledGMajorScale() {
        val loaded = ScoreLoader.load(assetBytes("g_major_scale.musicxml"))

        // The G major scale asset contains 8 quarter notes (G2 up to G3).
        assertTrue("expected at least one note", loaded.notes.isNotEmpty())
        assertEquals("expected 8 notes in the scale", 8, loaded.notes.size)

        // First note is G2: (2+1)*12 + 7 = 43.
        val first = loaded.notes.first()
        assertEquals("G2", first.name)
        assertEquals(43, first.midi)

        // Tempo comes from <sound tempo="60"> in the asset.
        assertTrue("bpm must be positive", loaded.bpm > 0.0)
        assertEquals(60.0, loaded.bpm, 1e-9)

        // Timing is baked at 60 BPM = 1 second per quarter note.
        assertEquals(0.0, first.start, 1e-9)
        assertEquals(1.0, first.end, 1e-9)
    }

    @Test
    fun parsesInlineMusicXml() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="3.1">
              <part-list><score-part id="P1"><part-name>T</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>2</divisions></attributes>
                  <sound tempo="90"/>
                  <note><pitch><step>A</step><octave>3</octave></pitch><duration>2</duration></note>
                  <note><rest/><duration>2</duration></note>
                  <note><pitch><step>C</step><alter>1</alter><octave>4</octave></pitch><duration>4</duration></note>
                </measure>
              </part>
            </score-partwise>
        """.trimIndent()

        val loaded = ScoreLoader.load(xml.toByteArray(Charsets.UTF_8))

        assertEquals(90.0, loaded.bpm, 1e-9)
        assertEquals(3, loaded.notes.size)

        // A3 = (3+1)*12 + 9 = 57, quarter-length 1 (duration 2 / divisions 2).
        val a3 = loaded.notes[0]
        assertEquals("A3", a3.name)
        assertEquals(57, a3.midi)
        assertEquals(0.0, a3.start, 1e-9)
        val secPerQuarter = 60.0 / 90.0
        assertEquals(secPerQuarter, a3.end, 1e-9)

        // Rest is kept on the timeline.
        val rest = loaded.notes[1]
        assertTrue("middle element should be a rest", rest.isRest)
        assertEquals("rest", rest.name)
        assertEquals(ScoreNote.REST, rest.midi)

        // C#4 = (4+1)*12 + 0 + 1 = 61, starts after A3 + rest (2 quarters).
        val cs4 = loaded.notes[2]
        assertEquals("C#4", cs4.name)
        assertEquals(61, cs4.midi)
        assertEquals(2.0 * secPerQuarter, cs4.start, 1e-9)
        assertEquals(4.0 * secPerQuarter, cs4.end, 1e-9)
    }

    @Test
    fun bpmOverrideWinsOverScoreTempo() {
        val xml = """
            <score-partwise>
              <part id="P1"><measure number="1">
                <attributes><divisions>1</divisions></attributes>
                <sound tempo="60"/>
                <note><pitch><step>G</step><octave>2</octave></pitch><duration>1</duration></note>
              </measure></part>
            </score-partwise>
        """.trimIndent()

        val loaded = ScoreLoader.load(xml.toByteArray(Charsets.UTF_8), bpmOverride = 120.0)
        assertEquals(120.0, loaded.bpm, 1e-9)
        // At 120 BPM a quarter note is 0.5s.
        assertEquals(0.5, loaded.notes.first().end, 1e-9)
    }

    @Test
    fun defaultsTo120WhenNoTempo() {
        val xml = """
            <score-partwise><part id="P1"><measure number="1">
              <attributes><divisions>1</divisions></attributes>
              <note><pitch><step>D</step><octave>3</octave></pitch><duration>1</duration></note>
            </measure></part></score-partwise>
        """.trimIndent()

        val loaded = ScoreLoader.load(xml.toByteArray(Charsets.UTF_8))
        assertEquals(120.0, loaded.bpm, 1e-9)
        assertNotNull(loaded.notes.first())
    }
}
