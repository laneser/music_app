package com.cellocoach.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Per-note scoring for the cello practice MVP.
 *
 * Direct port of `scorer.py` (plus the realtime status enum from `main.py` and
 * the automatic diagnostics described in the project README).
 *
 * A background tick (~50 ms) calls [Scorer.observe] with whichever note the
 * follower says is current and the detected Hz. Each call is bucketed into that
 * note and we keep three counts per note:
 *
 *  - `samples`        total ticks that fell inside this note's window
 *  - `voicedSamples`  ticks where any pitch was detected
 *  - `correctSamples` ticks where the detected MIDI matched the expected MIDI
 *
 * From those, per-note metrics:
 *
 *  - `sustain`    correctSamples / samples            in [0, 1]
 *  - `intonation` 1 − trimmedMean(|cents|) / 50       in [0, 1]
 *  - `score`      (0.5·intonation + 0.5·sustain) × 100
 *
 * A trimmed mean (drop top/bottom 20%) is used instead of a plain mean so a
 * wobbly attack at the start of a note doesn't drag the intonation reading
 * down. Rests don't accumulate samples — they neither help nor hurt.
 */

private fun log2(x: Double): Double = ln(x) / ln(2.0)

/**
 * Mean of [values] after discarding the top and bottom [trim] fraction.
 * Port of `scorer._trimmed_mean`.
 */
internal fun trimmedMean(values: List<Double>, trim: Double = 0.2): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val k = (s.size * trim).toInt()
    val middle = if (s.size - 2 * k >= 1) s.subList(k, s.size - k) else s
    return middle.sum() / middle.size
}

/**
 * Mutable accumulator for one expected note. Port of `scorer.NoteResult`;
 * properties mirror the Python `@property` getters exactly.
 */
class NoteResult(
    val index: Int,
    val expectedMidi: Int,
    val expectedName: String,
    val start: Double,
    val end: Double,
) {
    var samples: Int = 0
    var voicedSamples: Int = 0
    var correctSamples: Int = 0

    /** Recorded (calibration-corrected) cents error for each correct tick. */
    val centsValues: MutableList<Double> = mutableListOf()

    /**
     * Histogram of detected MIDI numbers — lets us answer "if the student got
     * this wrong, what did they actually play?".
     */
    val voicedMidiCounts: MutableMap<Int, Int> = mutableMapOf()

    val isRest: Boolean get() = expectedMidi < 0

    val sustain: Double
        get() = if (samples == 0) 0.0 else correctSamples.toDouble() / samples

    /** Trimmed mean of signed cents, or null when nothing matched. */
    val meanCents: Double?
        get() = if (centsValues.isEmpty()) null else trimmedMean(centsValues, 0.2)

    /** [0, 1] — 1.0 means dead-on; 0.0 means ≥50¢ off on average. */
    val intonation: Double
        get() {
            if (centsValues.isEmpty()) return 0.0
            val avgAbs = trimmedMean(centsValues.map { abs(it) }, 0.2)
            return maxOf(0.0, 1.0 - avgAbs / 50.0)
        }

    /**
     * For summary counts only — true when the player held the right note for
     * the majority of the window.
     */
    val pitchCorrect: Boolean get() = sustain > 0.5

    /** The MIDI value the player produced most often during this note. */
    val modalPlayedMidi: Int?
        get() = voicedMidiCounts.maxByOrNull { it.value }?.key

    val score: Double
        get() = if (isRest) 100.0 else (0.5 * intonation + 0.5 * sustain) * 100.0
}

/** One line of the per-note report. Port of the dict in `scorer.summary`. */
data class NoteSummary(
    val i: Int,
    val name: String,
    val expectedMidi: Int,
    val playedMidi: Int?,
    val score: Double,
    val pitchOk: Boolean,
    val cents: Double?,
    val sustain: Double,
)

/** The whole-session report. */
data class PracticeSummary(
    val score: Double,
    val nTotal: Int,
    val nCorrect: Int,
    val meanCents: Double?,
    val duration: Double,
    val notes: List<NoteSummary>,
    val diagnostics: List<String>,
)

/** Round to [decimals] places, matching Python's `round()`. */
private fun Double.roundTo(decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    return (this * factor).roundToInt() / factor
}

/**
 * Buckets detector readings into notes and produces the session [summary].
 * Port of `scorer.Scorer` with the diagnostics layer added on top.
 */
class Scorer(val notes: List<ScoreNote>, val tuning: Tuning = Tuning()) {

    val results: List<NoteResult> = notes.mapIndexed { i, n ->
        NoteResult(i, n.midi, n.name, n.start, n.end)
    }

    /**
     * Record one detector reading into the given note's stats. The follower
     * decides which note is current; we just record. Rests and out-of-range
     * indices are ignored. Port of `Scorer.observe`.
     */
    fun observe(noteIdx: Int, detectedHz: Float?) {
        if (noteIdx < 0 || noteIdx >= notes.size) return
        val n = notes[noteIdx]
        if (n.midi < 0) return // rest
        val r = results[noteIdx]
        r.samples += 1
        if (detectedHz == null || detectedHz <= 0f) return
        val hz = detectedHz.toDouble()
        val playedMidi = (69.0 + 12.0 * log2(hz / 440.0)).roundToInt()
        r.voicedSamples += 1
        r.voicedMidiCounts[playedMidi] = (r.voicedMidiCounts[playedMidi] ?: 0) + 1
        if (playedMidi == n.midi) {
            r.correctSamples += 1
            val rawCents = 1200.0 * log2(hz / n.freq)
            // Subtract the calibration offset for the string this note lives on,
            // so what's recorded reflects *playing* error, not instrument detune.
            r.centsValues.add(rawCents - tuning.offsetCentsForMidi(n.midi))
        }
    }

    /** Build the full [PracticeSummary]. Port of `Scorer.summary` + diagnostics. */
    fun summary(): PracticeSummary {
        val playable = results.filter { !it.isRest }
        if (playable.isEmpty()) {
            return PracticeSummary(
                score = 0.0,
                nTotal = 0,
                nCorrect = 0,
                meanCents = null,
                duration = 0.0,
                notes = emptyList(),
                diagnostics = emptyList(),
            )
        }

        val allCents = playable.flatMap { it.centsValues }
        val duration = if (notes.isNotEmpty()) notes.last().end else 0.0
        val meanCents = if (allCents.isNotEmpty()) trimmedMean(allCents, 0.2).roundTo(1) else null

        val noteSummaries = playable.map { r ->
            NoteSummary(
                i = r.index,
                name = r.expectedName,
                expectedMidi = r.expectedMidi,
                playedMidi = r.modalPlayedMidi,
                score = r.score.roundTo(1),
                pitchOk = r.pitchCorrect,
                cents = r.meanCents?.roundTo(1),
                sustain = r.sustain.roundTo(2),
            )
        }

        return PracticeSummary(
            score = (playable.sumOf { it.score } / playable.size).roundTo(1),
            nTotal = playable.size,
            nCorrect = playable.count { it.pitchCorrect },
            meanCents = meanCents,
            duration = duration.roundTo(2),
            notes = noteSummaries,
            diagnostics = buildDiagnostics(playable, meanCents),
        )
    }

    /**
     * Generate the Traditional-Chinese diagnostic strings described in the
     * README. Empty list when nothing notable is found.
     */
    private fun buildDiagnostics(playable: List<NoteResult>, meanCents: Double?): List<String> {
        val out = mutableListOf<String>()

        // 1) 整體偏移：|meanCents| ≥ 10 → 「整體音準偏{高/低} {x}¢」
        if (meanCents != null && abs(meanCents) >= 10.0) {
            val dir = if (meanCents > 0) "高" else "低"
            val mag = abs(meanCents).roundTo(1)
            out.add("整體音準偏$dir ${formatCents(mag)}¢")
        }

        // 2) 重複錯音：同一 expectedName 出現 ≥2 次且都 !pitchOk → 「{name} 反覆拉錯（{n} 次）」
        val byName = LinkedHashMap<String, MutableList<NoteResult>>()
        for (r in playable) byName.getOrPut(r.expectedName) { mutableListOf() }.add(r)
        for ((name, group) in byName) {
            if (group.size >= 2 && group.all { !it.pitchCorrect }) {
                out.add("$name 反覆拉錯（${group.size} 次）")
            }
        }

        // 3) 系統性偏移：某弦上的音平均 cents 偏移 ≥ 15 → 「{C/G/D/A} 弦的音普遍偏{高/低}」
        val byString = LinkedHashMap<String, MutableList<Double>>()
        for (r in playable) {
            if (r.centsValues.isEmpty()) continue
            val string = stringForMidi(r.expectedMidi)
            val noteMean = r.meanCents ?: continue
            byString.getOrPut(string) { mutableListOf() }.add(noteMean)
        }
        // Report strings in canonical C G D A order.
        for (string in STRING_NAMES) {
            val means = byString[string] ?: continue
            if (means.isEmpty()) continue
            val avg = means.sum() / means.size
            if (abs(avg) >= 15.0) {
                val dir = if (avg > 0) "高" else "低"
                out.add("$string 弦的音普遍偏$dir")
            }
        }

        return out
    }

    private fun formatCents(value: Double): String {
        // Match Python's "%.1f" but drop a redundant ".0" so whole numbers read cleanly.
        return if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}

/** Realtime feedback bucket. Port of the status enum produced in `main.py`. */
enum class PitchStatus { GOOD, CLOSE, OFF, WRONG }

/**
 * Realtime status for a single detector reading against the [expected] note,
 * applying the per-string calibration in [tuning]. Mirrors the branch in
 * `main.py`:
 *
 *  - wrong MIDI            → [PitchStatus.WRONG]
 *  - |cents| < 20          → [PitchStatus.GOOD]
 *  - |cents| < 50          → [PitchStatus.CLOSE]
 *  - otherwise             → [PitchStatus.OFF]
 */
fun pitchStatus(detectedHz: Double, expected: ScoreNote, tuning: Tuning): PitchStatus {
    val rawCents = 1200.0 * log2(detectedHz / expected.freq)
    val cents = rawCents - tuning.offsetCentsForMidi(expected.midi)
    val playedMidi = (69.0 + 12.0 * log2(detectedHz / 440.0)).roundToInt()
    return when {
        playedMidi != expected.midi -> PitchStatus.WRONG
        abs(cents) < 20.0 -> PitchStatus.GOOD
        abs(cents) < 50.0 -> PitchStatus.CLOSE
        else -> PitchStatus.OFF
    }
}
