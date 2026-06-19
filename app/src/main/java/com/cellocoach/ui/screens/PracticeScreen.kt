package com.cellocoach.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cellocoach.core.PitchStatus
import com.cellocoach.ui.FeedbackColors
import com.cellocoach.ui.PracticeViewModel
import com.cellocoach.ui.ScoreView
import com.cellocoach.ui.TestTags

/**
 * Practice screen.
 *
 * Combines the native notation view ([ScoreView], with its following cursor and
 * status-coloured current note) with the realtime feedback panel — expected vs
 * detected pitch, cents off, and the status colour — plus the 4-beat metronome
 * countdown shown before the score starts. This is the in-app equivalent of the
 * Flask page's OSMD + feedback panel + SSE stream.
 */
@Composable
fun PracticeScreen(vm: PracticeViewModel, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = vm.selectedScore,
                style = MaterialTheme.typography.titleMedium,
            )

            ScoreView(
                notes = vm.scoreNotes,
                currentNoteIdx = vm.currentNoteIdx,
                status = vm.status,
                bpm = vm.bpm,
            )

            FeedbackPanel(vm)

            OutlinedButton(
                onClick = { vm.resetPractice() },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.PRACTICE_RESET),
            ) {
                Text("重新開始")
            }
        }

        // Metronome countdown overlay (4 → 1) before the follower starts.
        if (vm.countdown > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = vm.countdown.toString(),
                    modifier = Modifier.testTag(TestTags.PRACTICE_COUNTDOWN),
                    color = Color.White,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Expected-vs-detected pitch, cents, and a colour-coded status chip. */
@Composable
private fun FeedbackPanel(vm: PracticeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("應拉", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = when {
                        vm.expectedIsRest -> "休止"
                        vm.expectedName != null ->
                            "${vm.expectedName}${vm.expectedHz?.let { " (${it} Hz)" } ?: ""}"
                        else -> "—"
                    },
                    modifier = Modifier.testTag(TestTags.PRACTICE_EXPECTED),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("你拉", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = vm.detectedHz?.let { "$it Hz" } ?: "—",
                    modifier = Modifier.testTag(TestTags.PRACTICE_DETECTED),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        Text(
            text = vm.cents?.let { c -> (if (c >= 0) "+" else "") + "$c ¢" } ?: "—",
            modifier = Modifier.testTag(TestTags.PRACTICE_CENTS),
            style = MaterialTheme.typography.headlineSmall,
        )

        StatusChip(vm.status)

        // The cursor index is surfaced as text too, so tests can assert advance.
        Text(
            text = "音 ${vm.currentNoteIdx + 1} / ${vm.totalNotes}",
            modifier = Modifier.testTag(TestTags.PRACTICE_CURSOR),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StatusChip(status: PitchStatus?) {
    val (label, color) = when (status) {
        PitchStatus.GOOD -> "準" to FeedbackColors.good
        PitchStatus.CLOSE -> "接近" to FeedbackColors.close
        PitchStatus.OFF -> "偏離" to FeedbackColors.off
        PitchStatus.WRONG -> "錯音" to FeedbackColors.wrong
        null -> "等待" to FeedbackColors.idle
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // testTag on the Text itself so assertTextEquals reads the label directly
        // (a tag on the Box would not merge the child's text).
        Text(
            label,
            modifier = Modifier.testTag(TestTags.PRACTICE_STATUS),
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}
