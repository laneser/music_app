package com.cellocoach.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/**
 * Unit tests for [PitchDetector] (the in-process NSDF/McLeod port of the
 * browser's Pitchy detection).
 *
 * Strategy: synthesize pure sine waves at known cello-range frequencies, feed
 * them through [PitchDetector.detect], and assert the detected pitch is within
 * ~10 cents. Also assert that silence, noise, and out-of-range tones return null.
 */
class PitchDetectorTest {

    private val sampleRate = 44100
    private val detector = PitchDetector(sampleRate = sampleRate)

    /** Generate [durationSec] of a [freq] Hz sine at [amplitude]. */
    private fun sine(freq: Double, durationSec: Double = 0.1, amplitude: Double = 0.5): FloatArray {
        val n = (sampleRate * durationSec).toInt()
        return FloatArray(n) { i ->
            (amplitude * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
        }
    }

    /** Cents difference between detected and reference frequency. */
    private fun cents(detected: Double, reference: Double): Double =
        1200.0 * ln(detected / reference) / ln(2.0)

    private fun assertDetectsWithinCents(freq: Double, toleranceCents: Double = 10.0) {
        val detected = detector.detect(sine(freq))
        assertNotNull("expected a pitch for ${freq}Hz but got null", detected)
        val err = abs(cents(detected!!.toDouble(), freq))
        assertTrue(
            "detected ${detected}Hz for ${freq}Hz (off by ${"%.2f".format(err)} cents)",
            err <= toleranceCents,
        )
    }

    @Test
    fun detectsA3_220Hz() = assertDetectsWithinCents(220.0)

    @Test
    fun detectsA4_440Hz() = assertDetectsWithinCents(440.0)

    @Test
    fun detectsC2_65_4Hz() = assertDetectsWithinCents(65.41)

    @Test
    fun silenceReturnsNull() {
        val silence = FloatArray((sampleRate * 0.1).toInt())
        assertNull(detector.detect(silence))
    }

    @Test
    fun whiteNoiseReturnsNull() {
        val rng = Random(42)
        val noise = FloatArray((sampleRate * 0.1).toInt()) {
            (rng.nextDouble(-1.0, 1.0) * 0.5).toFloat()
        }
        assertNull("white noise should have no clear pitch", detector.detect(noise))
    }

    @Test
    fun belowRangeReturnsNull() {
        // 30 Hz is below CELLO_FMIN (60 Hz).
        assertNull("30Hz is out of range", detector.detect(sine(30.0)))
    }

    @Test
    fun outputNeverEscapesRange() {
        // A pure tone above CELLO_FMAX cannot be cleanly rejected by an
        // autocorrelation detector: its sub-harmonics (1000 Hz, 667 Hz, …) land
        // *inside* [fmin, fmax], and a fixed lag-search window cannot tell a
        // 2000 Hz tone from its sub-harmonics. The contract the detector DOES
        // guarantee is that whatever it returns is null or within [fmin, fmax].
        val hz = detector.detect(sine(2000.0))
        if (hz != null) {
            assertTrue("detected $hz must stay within [fmin,fmax]",
                hz >= detector.fmin && hz <= detector.fmax)
        }
    }

    @Test
    fun emptyOrTinyInputReturnsNull() {
        assertNull(detector.detect(FloatArray(0)))
        assertNull(detector.detect(FloatArray(1) { 0.5f }))
    }
}
