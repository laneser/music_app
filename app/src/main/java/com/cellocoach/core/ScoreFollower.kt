package com.cellocoach.core

/**
 * Score follower — pitch-aware with timeout fallback.
 *
 * Line-by-line port of `score_follower.py`. Tracks where in the score the
 * player is by combining detected pitch with elapsed time:
 *
 *  - Stay on the current note while detected pitch matches it.
 *  - When detected pitch matches an *upcoming* note (1..[LOOKAHEAD] ahead),
 *    and that match is sustained for [ADVANCE_THRESHOLD_TICKS] ticks, jump to
 *    it. This handles "student played faster" and "student skipped a note".
 *  - If the player has dwelt on the current note for [TIMEOUT_FACTOR] x the
 *    note's expected duration, force-advance to the next one. Safety net for
 *    missed notes / failed pitch detection.
 *  - Repeated identical pitches (e.g. G G in a row) can't be split by pitch —
 *    fall back to timing for those (advance after 80% of scored duration).
 *  - Rests advance based on time only (their `midi` is negative).
 *
 * The follower is *forward-only*. Backward correction would need beam search,
 * which is out of scope for the MVP.
 *
 * The Python original called `time.monotonic()` (seconds). Here we inject a
 * [Clock] (default [SystemClock]) and derive seconds as `nowNanos() / 1e9`,
 * which lets unit tests drive time deterministically with a fake clock.
 */
class ScoreFollower(
    private val notes: List<ScoreNote>,
    private val clock: Clock = SystemClock,
) {
    private var idx: Int = -1                 // current note index; -1 = not started
    private var globalStart: Double? = null
    private var dwellStart: Double? = null
    // Hysteresis state for an upcoming-note match in progress.
    private var candidateTarget: Int? = null
    private var candidateCount: Int = 0

    /** Wall-clock seconds from the injected clock (Python `time.monotonic()`). */
    private fun now(): Double = clock.nowNanos() / 1e9

    fun start() {
        if (notes.isEmpty()) return
        val now = now()
        idx = 0
        globalStart = now
        dwellStart = now
        candidateTarget = null
        candidateCount = 0
    }

    fun started(): Boolean = globalStart != null

    fun isDone(): Boolean = started() && idx >= notes.size

    fun expectedNote(): ScoreNote? =
        if (idx in notes.indices) notes[idx] else null

    fun currentNoteIdx(): Int =
        if (idx in notes.indices) idx else -1

    /** Wall-clock seconds since [start]. For UI display only. */
    fun elapsed(): Double {
        val start = globalStart ?: return 0.0
        return now() - start
    }

    /**
     * Advance the follower based on what's being played right now.
     *
     * Call this once per detector tick, even when the detector returned no
     * pitch (pass `null`) — we use silence to time out stuck notes.
     */
    fun observe(detectedMidi: Int?) {
        if (!started() || isDone()) return

        val now = now()
        val dwell = now - (dwellStart ?: now)
        val current = notes[idx]
        val expectedDuration = maxOf(current.end - current.start, 0.05)

        // Hard timeout — past this, advance regardless of what we're hearing.
        if (dwell >= expectedDuration * TIMEOUT_FACTOR) {
            advance(now)
            return
        }

        // Rests have no pitch to match — advance when their time is up.
        if (current.midi < 0) {
            if (dwell >= expectedDuration) {
                advance(now)
            }
            return
        }

        // Repeated-note case: we can't tell two same-pitch notes apart by
        // pitch alone. After 80% of the scored duration, assume we've crossed
        // into the next one.
        if (idx + 1 < notes.size) {
            val nxt = notes[idx + 1]
            if (nxt.midi == current.midi && nxt.midi >= 0) {
                if (dwell >= expectedDuration * 0.8) {
                    advance(now)
                }
                return
            }
        }

        // If we still have no detection, do nothing (timeout above saves us).
        if (detectedMidi == null) {
            candidateTarget = null
            candidateCount = 0
            return
        }

        // On track — student is holding the right note.
        if (detectedMidi == current.midi) {
            candidateTarget = null
            candidateCount = 0
            return
        }

        // Look ahead for a match within LOOKAHEAD notes.
        var target: Int? = null
        for (k in 1..LOOKAHEAD) {
            val j = idx + k
            if (j >= notes.size) break
            if (notes[j].midi == detectedMidi && notes[j].midi >= 0) {
                target = j
                break
            }
        }

        if (target == null) {
            // Detected pitch matches neither current nor lookahead — likely a
            // mistake. Don't move; let the player correct.
            candidateTarget = null
            candidateCount = 0
            return
        }

        // Same lookahead target as last tick? Bump count. Otherwise restart.
        if (candidateTarget == target) {
            candidateCount += 1
        } else {
            candidateTarget = target
            candidateCount = 1
        }

        if (candidateCount >= ADVANCE_THRESHOLD_TICKS) {
            idx = target
            dwellStart = now
            candidateTarget = null
            candidateCount = 0
        }
    }

    private fun advance(now: Double) {
        idx += 1
        dwellStart = now
        candidateTarget = null
        candidateCount = 0
    }

    companion object {
        /**
         * Hysteresis: an upcoming-note match must persist this many ticks
         * before we trust it. At ~20 Hz this is ~250 ms.
         */
        const val ADVANCE_THRESHOLD_TICKS = 5

        /** How far ahead to look for matches (in number of notes). */
        const val LOOKAHEAD = 3

        /**
         * Force-advance when the student's been on a note this many times
         * longer than its scored duration.
         */
        const val TIMEOUT_FACTOR = 2.5
    }
}
