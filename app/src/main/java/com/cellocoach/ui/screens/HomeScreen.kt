package com.cellocoach.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cellocoach.ui.PracticeViewModel
import com.cellocoach.ui.TestTags

/**
 * Home screen — score picker, saved-calibration status, and the entry button.
 *
 * Ports the Flask page's startup affordances: choose a score from the
 * `assets/`-bundled list, see whether a saved tuning exists, and start. If the
 * tuning isn't calibrated, [PracticeViewModel.onStartPressed] routes through the
 * Tuning screen first; otherwise it goes straight to Practice.
 */
@Composable
fun HomeScreen(vm: PracticeViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // App name lives in the TopAppBar; here we just guide the next action
        // (the old duplicate "大提琴練習助手" headline was removed).
        Text(
            text = "選一首曲子開始練習",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Text("選擇曲目", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.HOME_SCORE_PICKER),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(vm.availableScores) { name ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.HOME_SCORE_OPTION_PREFIX + name)
                        .clickable { vm.selectScore(name) },
                    colors = CardDefaults.cardColors(),
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = name == vm.selectedScore,
                            onClick = { vm.selectScore(name) },
                        )
                        Text(name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        // Saved-calibration badge.
        val status = if (vm.hasSavedTuning) {
            "已校正${vm.savedTuningAt?.let { "（$it）" } ?: ""}"
        } else {
            "尚未校正（將先進行四弦校正）"
        }
        Text(
            text = status,
            modifier = Modifier.testTag(TestTags.HOME_TUNING_STATUS),
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = { vm.onStartPressed() },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.HOME_START),
        ) {
            Text("開始練習")
        }

        OutlinedButton(
            onClick = { vm.beginCalibration() },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.HOME_CALIBRATE),
        ) {
            Text("重新校正")
        }
    }
}
