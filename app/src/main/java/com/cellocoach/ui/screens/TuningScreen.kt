package com.cellocoach.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cellocoach.core.STRING_NAMES
import com.cellocoach.ui.PracticeViewModel
import com.cellocoach.ui.TestTags

/**
 * Tuning / calibration screen.
 *
 * Drives the open-string calibration in order C → G → D → A, mirroring
 * `_calibration_tick_loop`. Shows the current target string, the live cents
 * deviation, and a progress bar that fills over [PracticeViewModel.CALIB_REQUIRED_TICKS]
 * stable readings. "略過" skips to practice using A=440 as the reference.
 */
@Composable
fun TuningScreen(vm: PracticeViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("四弦校正", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Text(
            text = "請依序拉奏空弦：${STRING_NAMES.joinToString(" → ")}",
            style = MaterialTheme.typography.bodyMedium,
        )

        val target = vm.calibTarget
        Text(
            text = target?.let { "目前目標：$it 弦" } ?: "校正完成",
            modifier = Modifier.testTag(TestTags.TUNING_TARGET),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )

        val centsText = vm.calibCents?.let { c ->
            val sign = if (c >= 0) "+" else ""
            "$sign$c ¢"
        } ?: "—"
        Text(
            text = centsText,
            modifier = Modifier.testTag(TestTags.TUNING_CENTS),
            style = MaterialTheme.typography.headlineMedium,
        )

        LinearProgressIndicator(
            progress = { vm.calibProgress },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.TUNING_PROGRESS),
        )

        OutlinedButton(
            onClick = { vm.skipCalibration() },
            modifier = Modifier.testTag(TestTags.TUNING_SKIP),
        ) {
            Text("略過（使用 A=440）")
        }
    }
}
