package com.cellocoach.audio

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Tiny metronome click player.
 *
 * Uses [ToneGenerator] on the music stream for a short, transient beep. Two
 * intensities so the count-in down-beat is distinguishable from regular beats.
 *
 * Note on recording interference: a click played through the **speaker** is
 * picked up by the mic. In practice the pitch detector rejects most of it (the
 * click is broadband/transient → clarity below threshold, and it sits outside
 * the cello [PitchDetector] range), but **headphones are recommended** when the
 * metronome is on. The metronome is therefore opt-in beyond the count-in.
 */
class Metronome {

    @Volatile private var tone: ToneGenerator? = null

    private fun gen(): ToneGenerator? {
        if (tone == null) {
            tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        }
        return tone
    }

    /** A normal beat click. */
    fun tick() {
        gen()?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
    }

    /** A stronger click for the count-in down-beat / beat 1. */
    fun accent() {
        gen()?.startTone(ToneGenerator.TONE_PROP_BEEP2, 60)
    }

    fun release() {
        runCatching { tone?.release() }
        tone = null
    }
}
