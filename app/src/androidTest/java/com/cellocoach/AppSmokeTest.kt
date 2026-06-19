package com.cellocoach

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.cellocoach.ui.TestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Instrumented smoke test — runs on a real device / emulator over USB.
 *
 * Unlike the Robolectric flow tests (which inject a [com.cellocoach.audio.FakePitchSource]
 * and run on the JVM), this launches the real [MainActivity] end to end and only
 * asserts that the app boots to the Home screen. It deliberately does not touch the
 * microphone or drive practice — it is a "does the APK start and render" guard for
 * CI on hardware.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launches_and_shows_home_screen() {
        // The shell renders the screen container...
        composeTestRule.onNodeWithTag(TestTags.SCREEN_ROOT).assertIsDisplayed()
        // ...and Home's primary affordances are present: the start button, the
        // saved-tuning status badge, and the score picker.
        composeTestRule.onNodeWithTag(TestTags.HOME_START).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.HOME_TUNING_STATUS).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TestTags.HOME_SCORE_PICKER).assertIsDisplayed()
        // The home title text confirms we're on Home, not another screen.
        composeTestRule.onNodeWithText("開始練習").assertIsDisplayed()
    }
}
