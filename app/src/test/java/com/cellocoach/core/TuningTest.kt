package com.cellocoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [Tuning] port of `tuning.py`. Pure JVM, no Android.
 */
class TuningTest {

    @Test
    fun stringsAreCGDAInOrder() {
        assertEquals(listOf("C", "G", "D", "A"), STRING_NAMES)
        assertEquals(listOf("C" to 36, "G" to 43, "D" to 50, "A" to 57), STRINGS)
    }

    @Test
    fun nominalHzForStringMatchesA440() {
        // A3 = MIDI 57 = 220 Hz under A=440.
        assertEquals(220.0, nominalHzForString("A"), 1e-6)
    }

    @Test
    fun calibrateExactlyInTuneGivesZeroCents() {
        val tuning = Tuning()
        val offset = tuning.calibrateString("A", nominalHzForString("A"))
        assertEquals(0.0, offset, 1e-6)
    }

    @Test
    fun slightlySharpGivesPositiveCents() {
        val tuning = Tuning()
        // ~+10 cents sharp: nominal * 2^(10/1200).
        val sharpHz = nominalHzForString("A") * Math.pow(2.0, 10.0 / 1200.0)
        val offset = tuning.calibrateString("A", sharpHz)
        assertTrue("expected positive cents, got $offset", offset > 0)
        assertEquals(10.0, offset, 1e-6)
    }

    @Test
    fun slightlyFlatGivesNegativeCents() {
        val tuning = Tuning()
        val flatHz = nominalHzForString("G") * Math.pow(2.0, -7.0 / 1200.0)
        val offset = tuning.calibrateString("G", flatHz)
        assertTrue("expected negative cents, got $offset", offset < 0)
        assertEquals(-7.0, offset, 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun calibrateUnknownStringThrows() {
        Tuning().calibrateString("X", 100.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun calibrateNonPositiveHzThrows() {
        Tuning().calibrateString("A", 0.0)
    }

    @Test
    fun stringForMidiPicksHighestStringAtOrBelow() {
        assertEquals("C", stringForMidi(36))   // open C
        assertEquals("C", stringForMidi(42))   // between C and G
        assertEquals("G", stringForMidi(43))   // open G
        assertEquals("D", stringForMidi(55))   // between D and A
        assertEquals("A", stringForMidi(57))   // open A
        assertEquals("A", stringForMidi(72))   // above A
        assertEquals("C", stringForMidi(20))   // below lowest string -> C
    }

    @Test
    fun offsetCentsForMidiPicksRightStringViaStringForMidi() {
        val tuning = Tuning()
        tuning.calibrateString("D", nominalHzForString("D") * Math.pow(2.0, 5.0 / 1200.0))
        // MIDI 55 maps to the D string, so its offset applies.
        assertEquals(5.0, tuning.offsetCentsForMidi(55), 1e-6)
        // MIDI 36 maps to the (uncalibrated) C string -> zero.
        assertEquals(0.0, tuning.offsetCentsForMidi(36), 1e-6)
    }

    @Test
    fun isCalibratedOnlyTrueAfterAllFour() {
        val tuning = Tuning()
        assertFalse(tuning.isCalibrated())
        tuning.calibrateString("C", nominalHzForString("C"))
        tuning.calibrateString("G", nominalHzForString("G"))
        tuning.calibrateString("D", nominalHzForString("D"))
        assertFalse(tuning.isCalibrated())
        tuning.calibrateString("A", nominalHzForString("A"))
        assertTrue(tuning.isCalibrated())
    }

    @Test
    fun nextUncalibratedFollowsCGDAOrder() {
        val tuning = Tuning()
        assertEquals("C", tuning.nextUncalibrated())
        tuning.calibrateString("C", nominalHzForString("C"))
        assertEquals("G", tuning.nextUncalibrated())
        tuning.calibrateString("G", nominalHzForString("G"))
        assertEquals("D", tuning.nextUncalibrated())
        tuning.calibrateString("D", nominalHzForString("D"))
        assertEquals("A", tuning.nextUncalibrated())
        tuning.calibrateString("A", nominalHzForString("A"))
        assertNull(tuning.nextUncalibrated())
    }

    @Test
    fun clearForgetsCalibration() {
        val tuning = Tuning()
        tuning.calibrateString("A", nominalHzForString("A"))
        assertTrue(tuning.offsets.isNotEmpty())
        tuning.clear()
        assertTrue(tuning.offsets.isEmpty())
        assertEquals("C", tuning.nextUncalibrated())
    }

    @Test
    fun asMapRoundsToOneDecimal() {
        val tuning = Tuning()
        tuning.calibrateString("A", nominalHzForString("A") * Math.pow(2.0, 12.3456 / 1200.0))
        assertEquals(12.3, tuning.asMap()["A"]!!, 1e-9)
    }
}
