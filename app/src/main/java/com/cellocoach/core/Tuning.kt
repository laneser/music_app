package com.cellocoach.core

import kotlin.math.ln

/**
 * Per-string tuning calibration for cello.
 *
 * Port of `tuning.py`. A real cello is rarely exactly at A=440: the A string
 * drifts a few cents between sessions, and the C/G/D strings don't track A
 * perfectly because they use different tensions and ages. If we hardcoded
 * A=440, the "average cents" diagnostic would measure how out-of-tune the
 * *instrument* is, not how out-of-tune the *playing* is.
 *
 * So at the start of each session the student plays each open string. We record
 * the actual Hz of each, store the offset in cents from the nominal value, and
 * subtract that offset when computing cents for every subsequent note played on
 * that string.
 *
 * Persistence (the Python `save`/`load`/`saved_at` classmethods) lives in
 * `data/TuningStore.kt` on Android, so this file is pure algorithm and can be
 * unit-tested on the JVM with no Android dependencies.
 */

/**
 * `(string_name, nominal_midi)`. MIDI: C2=36, G2=43, D3=50, A3=57.
 *
 * Mirrors the Python `STRINGS` list of tuples.
 */
val STRINGS: List<Pair<String, Int>> = listOf(
    "C" to 36,
    "G" to 43,
    "D" to 50,
    "A" to 57,
)

/** Just the string names in standard low-to-high order: C, G, D, A. */
val STRING_NAMES: List<String> = STRINGS.map { it.first }

/**
 * Nominal (A=440) frequency in Hz for an open string.
 *
 * @throws NoSuchElementException if [name] is not a known string.
 */
fun nominalHzForString(name: String): Double {
    val midi = STRINGS.first { it.first == name }.second
    return midiToHz(midi)
}

/**
 * Pick the highest open string whose nominal MIDI is `<= midi`.
 *
 * This biases to "lowest position on a higher string", which is what most
 * students do in first–fourth position. Below the lowest string (C2) it returns
 * "C" so we still apply some offset.
 */
fun stringForMidi(midi: Int): String {
    var chosen = STRINGS[0].first
    for ((name, m) in STRINGS) {
        if (m <= midi) chosen = name
    }
    return chosen
}

/**
 * Cents offset per string. Empty until calibrated.
 *
 * Equivalent to the Python `Tuning` dataclass. [offsets] maps a string name to
 * its measured cents deviation from nominal pitch.
 */
class Tuning(val offsets: MutableMap<String, Double> = mutableMapOf()) {

    /** True only once all four strings have been calibrated. */
    fun isCalibrated(): Boolean = STRING_NAMES.all { it in offsets }

    /**
     * Record this string's offset (in cents) from its nominal pitch.
     *
     * A slightly sharp string yields a positive offset; flat yields negative.
     * Returns the offset for caller convenience.
     *
     * @throws IllegalArgumentException if [name] is unknown or [detectedHz] <= 0.
     */
    fun calibrateString(name: String, detectedHz: Double): Double {
        require(name in STRING_NAMES) { "Unknown string: $name" }
        require(detectedHz > 0) { "Invalid detectedHz: $detectedHz" }
        // 1200 * log2(detected / nominal); log2(x) = ln(x) / ln(2).
        val offset = 1200.0 * (ln(detectedHz / nominalHzForString(name)) / ln(2.0))
        offsets[name] = offset
        return offset
    }

    /**
     * How much to subtract from raw cents for a note played on the string this
     * MIDI most likely came from. Zero if that string is uncalibrated.
     */
    fun offsetCentsForMidi(midi: Int): Double {
        val name = stringForMidi(midi)
        return offsets[name] ?: 0.0
    }

    /** Forget all calibration. */
    fun clear() {
        offsets.clear()
    }

    /** Serializable copy with each offset rounded to 0.1 cent. */
    fun asMap(): Map<String, Double> =
        offsets.mapValues { (_, v) -> Math.round(v * 10.0) / 10.0 }

    /** The first string in standard order (C, G, D, A) not yet calibrated. */
    fun nextUncalibrated(): String? = STRING_NAMES.firstOrNull { it !in offsets }
}
