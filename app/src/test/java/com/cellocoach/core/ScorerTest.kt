package com.cellocoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Unit tests for the [Scorer] port of `scorer.py` + the realtime [pitchStatus]
 * helper + the Traditional-Chinese diagnostics layer.
 */
class ScorerTest {

    /** Equal-tempered Hz for a MIDI note, optionally detuned by [cents]. */
    private fun hzFor(midi: Int, cents: Double = 0.0): Float =
        (midiToHz(midi) * 2.0.pow(cents / 1200.0)).toFloat()

    /** A short three-note line (no rests) all on the D string region. */
    private fun threeNotes(): List<ScoreNote> = listOf(
        ScoreNote(0.0, 1.0, 50, "D3"),
        ScoreNote(1.0, 2.0, 52, "E3"),
        ScoreNote(2.0, 3.0, 54, "F#3"),
    )

    @Test
    fun `in-tune playing yields high score and full n_correct`() {
        val notes = threeNotes()
        val scorer = Scorer(notes)
        // 10 dead-on ticks per note.
        for (i in notes.indices) {
            repeat(10) { scorer.observe(i, hzFor(notes[i].midi)) }
        }
        val s = scorer.summary()
        assertEquals(3, s.nTotal)
        assertEquals(3, s.nCorrect)
        // Perfect intonation + full sustain → 100.
        assertEquals(100.0, s.score, 0.01)
        assertNotNull(s.meanCents)
        assertEquals(0.0, s.meanCents!!, 0.5)
        assertTrue(s.diagnostics.isEmpty())
    }

    @Test
    fun `consistently sharp playing gives positive meanCents and overall-offset diagnostic`() {
        val notes = threeNotes()
        val scorer = Scorer(notes)
        // Right note, but +30 cents sharp throughout.
        for (i in notes.indices) {
            repeat(10) { scorer.observe(i, hzFor(notes[i].midi, cents = 30.0)) }
        }
        val s = scorer.summary()
        assertNotNull(s.meanCents)
        assertTrue("meanCents should be positive (sharp)", s.meanCents!! > 0.0)
        assertEquals(30.0, s.meanCents!!, 1.0)
        // 整體偏移 fires at |meanCents| >= 10.
        assertTrue(
            "expected an overall-offset diagnostic",
            s.diagnostics.any { it.startsWith("整體音準偏高") },
        )
    }

    @Test
    fun `trimmed mean discards extreme outliers at the attack`() {
        // Single note. Most ticks dead-on, a couple wild outliers that a plain
        // mean would let through but the 20% trim drops.
        val notes = listOf(ScoreNote(0.0, 1.0, 50, "D3"))
        val scorer = Scorer(notes)
        // 8 dead-on, 1 way sharp (+40), 1 way flat (-40). 20% trim of 10 = 2,
        // dropping one from each tail → remaining are all 0¢.
        repeat(8) { scorer.observe(0, hzFor(50, 0.0)) }
        scorer.observe(0, hzFor(50, 40.0))
        scorer.observe(0, hzFor(50, -40.0))
        val r = scorer.results[0]
        assertEquals(10, r.centsValues.size)
        assertNotNull(r.meanCents)
        // Trimmed mean ≈ 0, whereas the plain mean would also be ~0 here; the
        // point is the magnitude stays tiny because tails are removed.
        assertEquals(0.0, r.meanCents!!, 0.5)
    }

    @Test
    fun `trimmed mean of skewed outliers stays near the body`() {
        val notes = listOf(ScoreNote(0.0, 1.0, 50, "D3"))
        val scorer = Scorer(notes)
        // 8 dead-on + 2 sharp outliers (+45 each). Plain mean = +9; trimmed
        // mean drops both tails: bottom 2 (0¢) and top 2 (one 0¢, one +45) →
        // it should land between, well under the plain mean.
        repeat(8) { scorer.observe(0, hzFor(50, 0.0)) }
        repeat(2) { scorer.observe(0, hzFor(50, 45.0)) }
        val r = scorer.results[0]
        val trimmed = r.meanCents!!
        val plain = r.centsValues.sum() / r.centsValues.size
        assertTrue("trimmed should be <= plain", trimmed <= plain + 0.01)
        assertTrue("trimmed should suppress the outliers", trimmed < 9.0)
    }

    @Test
    fun `rests are excluded from scoring`() {
        val notes = listOf(
            ScoreNote(0.0, 1.0, 50, "D3"),
            ScoreNote(1.0, 2.0, ScoreNote.REST, "rest"),
            ScoreNote(2.0, 3.0, 54, "F#3"),
        )
        val scorer = Scorer(notes)
        for (i in notes.indices) {
            repeat(10) { scorer.observe(i, hzFor(if (notes[i].isRest) 50 else notes[i].midi)) }
        }
        val s = scorer.summary()
        // Only the 2 playable notes count.
        assertEquals(2, s.nTotal)
        assertEquals(2, s.notes.size)
        assertTrue(s.notes.none { it.name == "rest" })
        // Observing into a rest accumulates nothing.
        assertEquals(0, scorer.results[1].samples)
    }

    @Test
    fun `wrong note has near-zero sustain and low score`() {
        val notes = threeNotes()
        val scorer = Scorer(notes)
        // Play a semitone above the expected note for the first note only.
        repeat(10) { scorer.observe(0, hzFor(notes[0].midi + 1)) }
        repeat(10) { scorer.observe(1, hzFor(notes[1].midi)) }
        repeat(10) { scorer.observe(2, hzFor(notes[2].midi)) }
        val s = scorer.summary()
        val first = s.notes.first { it.i == 0 }
        assertFalse(first.pitchOk)
        assertEquals(0.0, first.sustain, 0.001)
        assertEquals(51, first.playedMidi)
        assertEquals(2, s.nCorrect)
    }

    @Test
    fun `repeated wrong note produces a repeat-error diagnostic`() {
        // Same expected name appears twice and is wrong both times.
        val notes = listOf(
            ScoreNote(0.0, 1.0, 50, "D3"),
            ScoreNote(1.0, 2.0, 50, "D3"),
        )
        val scorer = Scorer(notes)
        repeat(10) { scorer.observe(0, hzFor(48)) } // wrong
        repeat(10) { scorer.observe(1, hzFor(48)) } // wrong again
        val s = scorer.summary()
        assertTrue(
            "expected a repeat-error diagnostic",
            s.diagnostics.any { it.contains("反覆拉錯") && it.contains("D3") },
        )
    }

    @Test
    fun `systematic per-string offset produces a string diagnostic`() {
        // Two notes both on the D string, all consistently +20 cents.
        val notes = listOf(
            ScoreNote(0.0, 1.0, 50, "D3"),
            ScoreNote(1.0, 2.0, 52, "E3"),
        )
        val scorer = Scorer(notes)
        for (i in notes.indices) {
            repeat(10) { scorer.observe(i, hzFor(notes[i].midi, cents = 20.0)) }
        }
        val s = scorer.summary()
        assertTrue(
            "expected a per-string offset diagnostic for D",
            s.diagnostics.any { it.startsWith("D 弦的音普遍偏高") },
        )
    }

    @Test
    fun `empty score summary is well-defined`() {
        val scorer = Scorer(emptyList())
        val s = scorer.summary()
        assertEquals(0.0, s.score, 0.0)
        assertEquals(0, s.nTotal)
        assertEquals(0, s.nCorrect)
        assertNull(s.meanCents)
        assertTrue(s.notes.isEmpty())
        assertTrue(s.diagnostics.isEmpty())
    }

    @Test
    fun `pitchStatus thresholds map cents and midi to the right bucket`() {
        val expected = ScoreNote(0.0, 1.0, 50, "D3")
        val tuning = Tuning()
        // Dead-on → GOOD.
        assertEquals(PitchStatus.GOOD, pitchStatus(midiToHz(50), expected, tuning))
        // +10¢ → GOOD (<20).
        assertEquals(
            PitchStatus.GOOD,
            pitchStatus(hzFor(50, 10.0).toDouble(), expected, tuning),
        )
        // +30¢ → CLOSE (<50).
        assertEquals(
            PitchStatus.CLOSE,
            pitchStatus(hzFor(50, 30.0).toDouble(), expected, tuning),
        )
        // +60¢ rounds to the next MIDI note (50.6 → 51), so per the original
        // main.py logic the nearest-note check fires first → WRONG.
        assertEquals(
            PitchStatus.WRONG,
            pitchStatus(hzFor(50, 60.0).toDouble(), expected, tuning),
        )
        // OFF (same nearest note but >=50¢ off) is only reachable once a
        // calibration offset is in play: detune the D string -60¢, then a raw
        // +40¢ reading still rounds to MIDI 50 but is 100¢ off after offset.
        val offTuning = Tuning()
        offTuning.calibrateString("D", hzFor(50, -60.0).toDouble())
        assertEquals(
            PitchStatus.OFF,
            pitchStatus(hzFor(50, 40.0).toDouble(), expected, offTuning),
        )
        // A different note entirely → WRONG.
        assertEquals(
            PitchStatus.WRONG,
            pitchStatus(midiToHz(52), expected, tuning),
        )
    }

    @Test
    fun `pitchStatus applies calibration offset before judging`() {
        // Instrument's D string is detuned +25¢; calibrate it, then a +25¢ raw
        // reading should read as dead-on (GOOD) after offset subtraction.
        val tuning = Tuning()
        tuning.calibrateString("D", hzFor(50, 25.0).toDouble())
        val expected = ScoreNote(0.0, 1.0, 50, "D3")
        assertEquals(
            PitchStatus.GOOD,
            pitchStatus(hzFor(50, 25.0).toDouble(), expected, tuning),
        )
    }
}
