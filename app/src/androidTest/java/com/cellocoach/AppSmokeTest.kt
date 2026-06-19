package com.cellocoach

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.cellocoach.ui.TestTags
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Instrumented smoke test — runs on a real device / emulator over USB (or the
 * KVM emulator via `scripts/run-emulator-tests.sh`).
 *
 * Unlike the Robolectric flow tests (which inject a [com.cellocoach.audio.FakePitchSource]
 * and run on the JVM), this launches the real [MainActivity] end to end and only
 * asserts that the app boots to the Home screen. It deliberately does not touch the
 * microphone or drive practice — it is a "does the APK start and render" guard for
 * CI on hardware.
 *
 * [GrantPermissionRule] pre-grants `RECORD_AUDIO` so the system permission dialog
 * never appears on a fresh install (that dialog would obscure the Activity and the
 * Compose hierarchy would not be found). [RuleChain] guarantees the grant is applied
 * *before* [createAndroidComposeRule] launches the Activity.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    private val composeTestRule = createAndroidComposeRule<MainActivity>()
    private val grant = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(grant).around(composeTestRule)

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
