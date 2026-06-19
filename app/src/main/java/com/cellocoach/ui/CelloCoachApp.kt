package com.cellocoach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.cellocoach.ui.screens.HomeScreen
import com.cellocoach.ui.screens.PracticeScreen
import com.cellocoach.ui.screens.ReportScreen
import com.cellocoach.ui.screens.TuningScreen

/**
 * Root composable. Owns the [CelloCoachTheme] and switches between the four
 * screens purely off [PracticeViewModel.screen] — no navigation library, just a
 * `when` over the [Screen] enum (the contract explicitly forbids a nav dep).
 *
 * The [PracticeViewModel] is passed in (not created here) so [com.cellocoach.MainActivity]
 * can inject the real [com.cellocoach.audio.AudioPitchSource] while Robolectric
 * tests inject a [com.cellocoach.audio.FakePitchSource] to drive the flow
 * deterministically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CelloCoachApp(vm: PracticeViewModel) {
    CelloCoachTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(titleFor(vm.screen)) },
                        navigationIcon = {
                            if (vm.screen != Screen.HOME) {
                                IconButton(
                                    onClick = { vm.goHome() },
                                    modifier = Modifier.testTag(TestTags.NAV_HOME),
                                ) {
                                    Icon(Icons.Filled.Home, contentDescription = "首頁")
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(TestTags.SCREEN_ROOT),
                ) {
                    when (vm.screen) {
                        Screen.HOME -> HomeScreen(vm, Modifier.padding(padding))
                        Screen.TUNING -> TuningScreen(vm, Modifier.padding(padding))
                        Screen.PRACTICE -> PracticeScreen(vm, Modifier.padding(padding))
                        Screen.REPORT -> ReportScreen(vm, Modifier.padding(padding))
                    }
                }
            }
        }
    }
}

private fun titleFor(screen: Screen): String = when (screen) {
    Screen.HOME -> "大提琴練習助手"
    Screen.TUNING -> "校正"
    Screen.PRACTICE -> "練習"
    Screen.REPORT -> "報告"
}
