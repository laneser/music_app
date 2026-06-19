package com.cellocoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [ScoreFollower] port of `score_follower.py`.
 *
 * Time is driven by a [FakeClock] (mutable nanos) so each branch of the
 * follower's logic — start, staying put, lookahead advance, hard timeout,
 * rest-by-time, repeated-note 80% advance — is exercised deterministically.
 */
class ScoreFollowerTest {

    /** Deterministic [Clock]: tests set [seconds] directly; nanos = seconds * 1e9. */
    private class FakeClock(var nanos: Long = 0L) : Clock {
        override fun nowNanos(): Long = nanos
        var seconds: Double
            get() = nanos / 1e9
            set(value) { nanos = (value * 1e9).toLong() }
    }

    /** Helper: a one-second note from [start] with the given [midi]. */
    private fun note(start: Double, midi: Int, name: String = "n", len: Double = 1.0) =
        ScoreNote(start = start, end = start + len, midi = midi, name = name)

    @Test
    fun start_setsUpFirstNote() {
        val clock = FakeClock()
        val follower = ScoreFollower(listOf(note(0.0, 60)), clock)

        assertFalse(follower.started())
        assertNull(follower.expectedNote())
        assertEquals(-1, follower.currentNoteIdx())

        follower.start()

        assertTrue(follower.started())
        assertFalse(follower.isDone())
        assertEquals(0, follower.currentNoteIdx())
        assertEquals(60, follower.expectedNote()?.midi)
    }

    @Test
    fun start_isNoOpForEmptyScore() {
        val follower = ScoreFollower(emptyList(), FakeClock())
        follower.start()
        assertFalse(follower.started())
    }

    @Test
    fun staysOnCurrentNoteWhileCorrectMidiObserved() {
        val clock = FakeClock()
        val follower = ScoreFollower(
            listOf(note(0.0, 60), note(1.0, 62)),
            clock,
        )
        follower.start()

        // Observe the correct midi several times within the duration window.
        repeat(10) {
            clock.seconds += 0.05
            follower.observe(60)
        }
        assertEquals(0, follower.currentNoteIdx())
    }

    @Test
    fun lookaheadAdvancesAfterThresholdConsecutiveMatches() {
        val clock = FakeClock()
        // Current note 60; an upcoming note (idx 2) is 64.
        val follower = ScoreFollower(
            listOf(note(0.0, 60), note(1.0, 62), note(2.0, 64)),
            clock,
        )
        follower.start()

        // Observe the upcoming-note midi (64) for ADVANCE_THRESHOLD_TICKS - 1
        // ticks: not yet enough, still on note 0. Keep time inside the
        // current note's duration so no timeout/repeat path fires.
        repeat(ScoreFollower.ADVANCE_THRESHOLD_TICKS - 1) {
            follower.observe(64)
            assertEquals(0, follower.currentNoteIdx())
        }

        // The 5th consecutive match crosses the threshold → jump to idx 2.
        follower.observe(64)
        assertEquals(2, follower.currentNoteIdx())
        assertEquals(64, follower.expectedNote()?.midi)
    }

    @Test
    fun lookaheadResetsWhenTargetChanges() {
        val clock = FakeClock()
        val follower = ScoreFollower(
            listOf(note(0.0, 60), note(1.0, 62), note(2.0, 64)),
            clock,
        )
        follower.start()

        // Alternate between two different lookahead targets so the candidate
        // count never reaches the threshold.
        repeat(10) {
            follower.observe(62) // target idx 1
            follower.observe(64) // target idx 2 — resets count
        }
        assertEquals(0, follower.currentNoteIdx())
    }

    @Test
    fun hardTimeoutAdvancesAfterSilence() {
        val clock = FakeClock()
        val follower = ScoreFollower(
            listOf(note(0.0, 60), note(1.0, 62)),
            clock,
        )
        follower.start()

        // expected_duration = 1.0; hard timeout at TIMEOUT_FACTOR (2.5) x = 2.5s.
        clock.seconds = 2.4
        follower.observe(null)
        assertEquals(0, follower.currentNoteIdx())

        clock.seconds = 2.5
        follower.observe(null)
        assertEquals(1, follower.currentNoteIdx())
    }

    @Test
    fun restAdvancesByTimeAlone() {
        val clock = FakeClock()
        val follower = ScoreFollower(
            listOf(note(0.0, ScoreNote.REST, name = "rest"), note(1.0, 62)),
            clock,
        )
        follower.start()

        // Rest duration 1.0; advances once dwell >= duration (before timeout).
        clock.seconds = 0.9
        follower.observe(null)
        assertEquals(0, follower.currentNoteIdx())

        clock.seconds = 1.0
        follower.observe(null)
        assertEquals(1, follower.currentNoteIdx())
        assertEquals(62, follower.expectedNote()?.midi)
    }

    @Test
    fun repeatedNoteAdvancesAtEightyPercent() {
        val clock = FakeClock()
        // Two identical pitches in a row: can't be split by pitch.
        val follower = ScoreFollower(
            listOf(note(0.0, 60), note(1.0, 60), note(2.0, 64)),
            clock,
        )
        follower.start()

        clock.seconds = 0.7
        follower.observe(60)
        assertEquals(0, follower.currentNoteIdx())

        // 80% of 1.0s duration → advance into the second identical note.
        clock.seconds = 0.8
        follower.observe(60)
        assertEquals(1, follower.currentNoteIdx())
    }

    @Test
    fun isDoneAtEndOfScore() {
        val clock = FakeClock()
        val follower = ScoreFollower(listOf(note(0.0, 60)), clock)
        follower.start()
        assertFalse(follower.isDone())

        // Single note: hard timeout advances idx past the end.
        clock.seconds = 2.5
        follower.observe(null)

        assertTrue(follower.isDone())
        assertEquals(-1, follower.currentNoteIdx())
        assertNull(follower.expectedNote())

        // observe() is a no-op once done.
        follower.observe(60)
        assertTrue(follower.isDone())
    }

    @Test
    fun elapsedReflectsClock() {
        val clock = FakeClock()
        val follower = ScoreFollower(listOf(note(0.0, 60)), clock)
        assertEquals(0.0, follower.elapsed(), 1e-9)

        follower.start()
        clock.seconds = 3.25
        assertEquals(3.25, follower.elapsed(), 1e-9)
    }
}
