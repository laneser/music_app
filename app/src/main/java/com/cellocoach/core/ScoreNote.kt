package com.cellocoach.core

/**
 * One note (or rest) on the flattened, single-voice timeline.
 *
 * Mirrors `score_loader.ScoreNote` from the original Python project: times are
 * in seconds from the start of the score, [midi] is the MIDI note number, and a
 * rest is encoded as [midi] == [REST].
 */
data class ScoreNote(
    val start: Double,
    val end: Double,
    val midi: Int,
    val name: String,
) {
    val isRest: Boolean get() = midi < 0

    /** Nominal frequency at A=440 equal temperament; 0 for rests. */
    val freq: Double
        get() = if (midi < 0) 0.0 else midiToHz(midi)

    companion object {
        const val REST = -1
    }
}

/** A=440 equal-tempered conversion. Shared by every module that touches pitch. */
fun midiToHz(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

fun midiToHz(midi: Double): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

/** Nearest MIDI integer for a frequency in Hz. */
fun hzToMidi(hz: Double): Int = Math.round(69.0 + 12.0 * (Math.log(hz / 440.0) / Math.log(2.0))).toInt()

/** Cents difference of [hz] above [refHz]. */
fun centsBetween(hz: Double, refHz: Double): Double =
    1200.0 * (Math.log(hz / refHz) / Math.log(2.0))
