package com.cellocoach.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.cellocoach.audio.FakePitchSource
import com.cellocoach.core.PitchFrame
import com.cellocoach.core.STRING_NAMES
import com.cellocoach.core.nominalHzForString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose end-to-end test of the four-string calibration flow.
 *
 * This is the "mock microphone" screen test the contract calls for: it injects a
 * [FakePitchSource] in place of [com.cellocoach.audio.AudioPitchSource], so the
 * whole Home -> Tuning flow runs on the JVM with no emulator and no real audio
 * hardware.
 *
 * Determinism strategy
 * --------------------
 * [PracticeViewModel] runs its 50 ms tick loop on `viewModelScope` via
 * `delay(TICK_MS)`. Under a Compose test rule those `delay`s are driven by the
 * test's main clock, so we disable [androidx.compose.ui.test.MainTestClock.autoAdvance]
 * and step time ourselves: emit one [PitchFrame], advance the clock by exactly one
 * tick (50 ms) to fire a single `_calibration_tick_loop` iteration, then assert.
 * This mirrors how a stable open string would be sampled ~20 times/sec on a real
 * device, but fully under test control — no flakiness from wall-clock timing.
 *
 * The calibration loop accepts a string after
 * [PracticeViewModel.CALIB_REQUIRED_TICKS] consecutive stable readings, then
 * advances C -> G -> D -> A and finally persists via `TuningStore` before jumping
 * to Practice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalibrationFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fake = FakePitchSource()
    private lateinit var vm: PracticeViewModel

    /** Build the ViewModel with the injected fake source and mount the app. */
    private fun launchApp(): PracticeViewModel {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        // autoDrive=false: the Compose test clock does not drive viewModelScope
        // `delay`s, so we step the engine ourselves via vm.stepForTest().
        vm = PracticeViewModel(app, fake, autoDrive = false)
        composeTestRule.setContent { CelloCoachApp(vm) }
        composeTestRule.waitForIdle()
        return vm
    }

    /** Emit one voiced frame, fire exactly one calibration tick, then recompose. */
    private fun tick(hz: Float?, rms: Float = 0.2f) {
        fake.emit(PitchFrame(hz, rms))
        vm.stepForTest()
        composeTestRule.waitForIdle()
    }

    /** Feed enough perfectly-in-tune ticks to fully calibrate one open string. */
    private fun calibrateOneString(stringName: String) {
        val hz = nominalHzForString(stringName).toFloat()
        // One extra tick than required: the acceptance happens on the tick that
        // reaches CALIB_REQUIRED_TICKS, advancing the target to the next string.
        repeat(PracticeViewModel.CALIB_REQUIRED_TICKS) { tick(hz) }
    }

    @Test
    fun `tuning advances C to G to D to A and persists`() {
        val vm = launchApp()

        // Home -> begin calibration (no saved tuning, so Start routes to Tuning).
        composeTestRule.onNodeWithTag(TestTags.HOME_START).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Screen.TUNING, vm.screen)
        // First target is the lowest string, C.
        assertEquals("C", vm.calibTarget)
        composeTestRule.onNodeWithTag(TestTags.TUNING_TARGET).assertTextContains("C", substring = true)

        // Calibrate each string in order and assert the target advances.
        calibrateOneString("C")
        assertEquals("G", vm.calibTarget)
        composeTestRule.onNodeWithTag(TestTags.TUNING_TARGET).assertTextContains("G", substring = true)

        calibrateOneString("G")
        assertEquals("D", vm.calibTarget)
        composeTestRule.onNodeWithTag(TestTags.TUNING_TARGET).assertTextContains("D", substring = true)

        calibrateOneString("D")
        assertEquals("A", vm.calibTarget)
        composeTestRule.onNodeWithTag(TestTags.TUNING_TARGET).assertTextContains("A", substring = true)

        // Final string completes calibration: target clears, tuning persists, and
        // the flow jumps straight to Practice (port of `_calibration_tick_loop`).
        calibrateOneString("A")
        assertNull(vm.calibTarget)
        assertTrue("all four strings calibrated", vm.tuning.isCalibrated())
        assertEquals(STRING_NAMES.toSet(), vm.tuning.offsets.keys)
        assertTrue("tuning was persisted", vm.hasSavedTuning)
        assertEquals(Screen.PRACTICE, vm.screen)
    }

    @Test
    fun `progress bar fills as stable readings accumulate`() {
        val vm = launchApp()
        composeTestRule.onNodeWithTag(TestTags.HOME_START).performClick()
        composeTestRule.waitForIdle()

        val hz = nominalHzForString("C").toFloat()
        // Halfway through the required ticks the progress should be ~0.5.
        repeat(PracticeViewModel.CALIB_REQUIRED_TICKS / 2) { tick(hz) }
        assertEquals("C", vm.calibTarget) // not accepted yet
        assertTrue("progress climbing", vm.calibProgress > 0.4f && vm.calibProgress < 0.6f)
        composeTestRule.onNodeWithTag(TestTags.TUNING_PROGRESS).assertExists()
    }

    @Test
    fun `silence resets calibration progress`() {
        val vm = launchApp()
        composeTestRule.onNodeWithTag(TestTags.HOME_START).performClick()
        composeTestRule.waitForIdle()

        val hz = nominalHzForString("C").toFloat()
        repeat(5) { tick(hz) }
        assertTrue(vm.calibProgress > 0f)
        // A null (silent) frame clears the buffer — student stopped bowing.
        tick(null)
        assertEquals(0f, vm.calibProgress, 0.0001f)
        assertNull(vm.calibCents)
        assertEquals("C", vm.calibTarget)
    }

    @Test
    fun `skip works and goes straight to practice with A=440 reference`() {
        val vm = launchApp()
        composeTestRule.onNodeWithTag(TestTags.HOME_START).performClick()
        composeTestRule.waitForIdle()
        assertEquals(Screen.TUNING, vm.screen)

        // Tap "略過（使用 A=440）".
        composeTestRule.onNodeWithTag(TestTags.TUNING_SKIP).performClick()
        composeTestRule.waitForIdle()

        // Skipping leaves the tuning uncalibrated (cents fall back to A=440) and
        // jumps to Practice without persisting anything.
        assertEquals(Screen.PRACTICE, vm.screen)
        assertNull(vm.calibTarget)
        assertFalse(vm.tuning.isCalibrated())
        assertFalse(vm.hasSavedTuning)
    }
}
