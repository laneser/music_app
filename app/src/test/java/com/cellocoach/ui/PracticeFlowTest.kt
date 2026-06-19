package com.cellocoach.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.cellocoach.audio.FakePitchSource
import com.cellocoach.core.Clock
import com.cellocoach.core.PitchFrame
import com.cellocoach.core.ScoreFollower
import com.cellocoach.core.midiToHz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.pow

/**
 * Robolectric Compose end-to-end test of the practice flow on `g_major_scale`.
 *
 * Like [CalibrationFlowTest] this injects a [FakePitchSource] (the "mock
 * microphone") so the full Home -> Practice -> Report flow runs on the JVM with no
 * emulator and no audio hardware.
 *
 * Two clocks are stepped explicitly so the test is fully deterministic:
 *  - the **Compose main clock**, which drives the ViewModel's 50 ms `delay(TICK_MS)`
 *    tick loop and the metronome countdown's `delay(beatMs)`; and
 *  - an injected [FakeClock] handed to the [ScoreFollower] (the additive test hook
 *    on [PracticeViewModel]) so the time-based final-note timeout in
 *    `score_follower.py` fires on demand instead of relying on wall-clock time the
 *    Compose clock does not advance.
 *
 * Cursor advance through the body of the score is driven purely by *pitch*: the
 * follower's look-ahead jumps to the next note after [ScoreFollower.ADVANCE_THRESHOLD_TICKS]
 * consecutive ticks of that note's pitch. The follower clock is held still during
 * those ticks so dwell-based timeouts can't interfere; only the very last note is
 * finished via an explicit clock jump past its timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PracticeFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fake = FakePitchSource()

    /** Controllable monotonic clock for the [ScoreFollower] inside the ViewModel. */
    private class FakeClock(var nanos: Long = 0L) : Clock {
        override fun nowNanos(): Long = nanos
        fun advanceSeconds(s: Double) { nanos += (s * 1e9).toLong() }
    }

    private val clock = FakeClock()
    private lateinit var vm: PracticeViewModel

    // g_major_scale.musicxml: G2 A2 B2 C3 D3 E3 F#3 G3, one quarter each at bpm 60.
    private val expectedMidis = intArrayOf(43, 45, 47, 48, 50, 52, 54, 55)

    private fun launchApp(): PracticeViewModel {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        // autoDrive=false: step the engine via vm.stepForTest(); the metronome
        // countdown is collapsed so practice starts immediately.
        vm = PracticeViewModel(app, fake, clock = clock, autoDrive = false)
        composeTestRule.setContent { CelloCoachApp(vm) }
        composeTestRule.waitForIdle()
        return vm
    }

    /** Equal-tempered Hz for a MIDI note, optionally detuned by [cents]. */
    private fun hzFor(midi: Int, cents: Double = 0.0): Float =
        (midiToHz(midi) * 2.0.pow(cents / 1200.0)).toFloat()

    /**
     * Emit one frame, then fire exactly one 50 ms tick of the ViewModel loop.
     * The follower clock is left untouched so pitch — not time — decides advance.
     */
    private fun tick(hz: Float?, rms: Float = 0.2f) {
        fake.emit(PitchFrame(hz, rms))
        vm.stepForTest()
        composeTestRule.waitForIdle()
    }

    /** In autoDrive=false the lead-in is collapsed, so the follower is already
     *  started on entering Practice — just assert that. */
    private fun runCountdown(vm: PracticeViewModel) {
        composeTestRule.waitForIdle()
        assertTrue("follower should have started", vm.started)
        assertEquals(0, vm.countdown)
    }

    /** From Home, skip calibration and reach a started Practice screen. */
    private fun startPractice(vm: PracticeViewModel) {
        composeTestRule.onNodeWithTag(TestTags.HOME_START).performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(Screen.TUNING, vm.screen)
        composeTestRule.onNodeWithTag(TestTags.TUNING_SKIP).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Screen.PRACTICE, vm.screen)
        runCountdown(vm)
    }

    /**
     * Hold the current note in tune for [dwell] ticks (banking correct scorer
     * samples), then push the *next* note's pitch for [ScoreFollower.ADVANCE_THRESHOLD_TICKS]
     * ticks so the look-ahead jumps the cursor forward. Returns once the cursor is
     * on [next].
     */
    private fun dwellThenAdvanceTo(vm: PracticeViewModel, current: Int, next: Int, dwell: Int = 10) {
        repeat(dwell) { tick(hzFor(expectedMidis[current])) }
        repeat(ScoreFollower.ADVANCE_THRESHOLD_TICKS) { tick(hzFor(expectedMidis[next])) }
        assertEquals("cursor should have advanced to note $next", next, vm.currentNoteIdx)
    }

    @Test
    fun `correct pitches advance the cursor and show good status`() {
        val vm = launchApp()
        startPractice(vm)

        // Cursor starts on note 0 (G2).
        assertEquals(0, vm.currentNoteIdx)
        composeTestRule.onNodeWithTag(TestTags.PRACTICE_CURSOR)
            .assertTextContains("1 /", substring = true)

        // Play note 0 dead-on for a few ticks -> stays on 0, status GOOD.
        repeat(3) { tick(hzFor(expectedMidis[0])) }
        assertEquals(0, vm.currentNoteIdx)
        assertEquals(com.cellocoach.core.PitchStatus.GOOD, vm.status)
        assertEquals("G2", vm.expectedName)
        composeTestRule.onNodeWithTag(TestTags.PRACTICE_STATUS).assertTextEquals("準")
        composeTestRule.onNodeWithTag(TestTags.PRACTICE_EXPECTED)
            .assertTextContains("G2", substring = true)

        // Walk forward one note at a time via the look-ahead advance.
        for (i in 1 until expectedMidis.size) {
            dwellThenAdvanceTo(vm, current = i - 1, next = i)
            // Now correctly playing note i -> GOOD, and the cursor text updated.
            tick(hzFor(expectedMidis[i]))
            assertEquals(com.cellocoach.core.PitchStatus.GOOD, vm.status)
            composeTestRule.onNodeWithTag(TestTags.PRACTICE_CURSOR)
                .assertTextContains("${i + 1} /", substring = true)
        }

        // On the last note now.
        assertEquals(expectedMidis.lastIndex, vm.currentNoteIdx)
        composeTestRule.onNodeWithTag(TestTags.PRACTICE_CURSOR)
            .assertTextContains("${expectedMidis.size} /", substring = true)
    }

    @Test
    fun `a wrong pitch shows wrong status and red feedback`() {
        val vm = launchApp()
        startPractice(vm)

        // Expected note 0 is G2 (43). Play a clearly wrong note far away that is
        // NOT within the look-ahead window (so the cursor does not jump): a high A4.
        repeat(3) { tick(hzFor(81)) } // A4, +much higher, not in the next 3 notes
        assertEquals("cursor must not advance on a wrong note", 0, vm.currentNoteIdx)
        assertEquals(com.cellocoach.core.PitchStatus.WRONG, vm.status)
        composeTestRule.onNodeWithTag(TestTags.PRACTICE_STATUS).assertTextEquals("錯音")

        // The right MIDI but noticeably sharp (35 cents) reads as CLOSE, not GOOD:
        // the cents stay within the same semitone bucket so playedMidi == expected,
        // and 20 <= |cents| < 50 maps to CLOSE ("接近"). (PitchStatus.OFF is not
        // reachable with a single detuned note — |cents| >= 50 rounds to a
        // neighbouring MIDI, which the engine reports as WRONG instead.)
        tick(hzFor(expectedMidis[0], cents = 35.0))
        assertEquals(com.cellocoach.core.PitchStatus.CLOSE, vm.status)
        composeTestRule.onNodeWithTag(TestTags.PRACTICE_STATUS).assertTextEquals("接近")
    }

    @Test
    fun `reaching the end shows the report with score X of Y and a note list`() {
        val vm = launchApp()
        startPractice(vm)

        // Play every note: dwell in tune on each (banking correct samples) then
        // advance to the next via the look-ahead.
        for (i in 1 until expectedMidis.size) {
            dwellThenAdvanceTo(vm, current = i - 1, next = i)
        }
        // Bank plenty of in-tune samples on the final note so it scores correct.
        repeat(8) { tick(hzFor(expectedMidis.last())) }

        // Finish the final note via the time-based hard timeout: jump the follower
        // clock past TIMEOUT_FACTOR x its 1.0 s duration, then fire one more tick.
        clock.advanceSeconds(ScoreFollower.TIMEOUT_FACTOR * 1.0 + 0.5)
        tick(hzFor(expectedMidis.last()))

        // The ViewModel snapshots the summary and switches to the Report screen.
        composeTestRule.waitForIdle()
        assertEquals(Screen.REPORT, vm.screen)
        val summary = vm.summary
        assertNotNull("summary must be produced", summary)
        requireNotNull(summary)
        assertEquals(expectedMidis.size, summary.nTotal)
        // Everything was played in tune -> all notes correct.
        assertEquals(summary.nTotal, summary.nCorrect)
        assertTrue("score should be high for in-tune play", summary.score >= 80.0)

        // Report screen widgets carry the expected numbers.
        composeTestRule.onNodeWithTag(TestTags.REPORT_SCORE)
            .assertTextContains("${summary.score}", substring = true)
        composeTestRule.onNodeWithTag(TestTags.REPORT_CORRECT)
            .assertTextContains("${summary.nCorrect} / ${summary.nTotal}", substring = true)
        composeTestRule.onNodeWithTag(TestTags.REPORT_NOTE_LIST).assertExists()
        // First note row exists in the per-note detail list.
        composeTestRule.onNodeWithTag(TestTags.REPORT_NOTE_ROW_PREFIX + "0").assertExists()
        composeTestRule.onNodeWithTag(TestTags.REPORT_DONE).assertExists()
    }
}
