package com.cellocoach.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cellocoach.core.NoteSummary
import com.cellocoach.ui.FeedbackColors
import com.cellocoach.ui.PracticeViewModel
import com.cellocoach.ui.TestTags

/**
 * Practice report.
 *
 * Renders the [com.cellocoach.core.PracticeSummary] produced by the [Scorer] at
 * the end of a run: overall score, X/Y notes correct, trimmed-mean cents, a
 * per-note detail list, and the Traditional-Chinese diagnostics. Equivalent to
 * the summary the Python `print_summary` printed and the web UI showed.
 */
@Composable
fun ReportScreen(vm: PracticeViewModel, modifier: Modifier = Modifier) {
    val summary = vm.summary
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("練習報告", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        if (summary == null) {
            Text("尚無報告")
            Button(onClick = { vm.goHome() }) { Text("回首頁") }
            return@Column
        }

        Text(
            text = "總分 ${summary.score}",
            modifier = Modifier.testTag(TestTags.REPORT_SCORE),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "對音 ${summary.nCorrect} / ${summary.nTotal}",
                modifier = Modifier.testTag(TestTags.REPORT_CORRECT),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "平均 ${summary.meanCents?.let { "${it}¢" } ?: "—"}",
                modifier = Modifier.testTag(TestTags.REPORT_MEAN_CENTS),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // --- Diagnostics ---
        Card(modifier = Modifier.fillMaxWidth().testTag(TestTags.REPORT_DIAGNOSTICS)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("診斷", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (summary.diagnostics.isEmpty()) {
                    Text("沒有發現明顯問題，繼續保持！")
                } else {
                    summary.diagnostics.forEach { Text("• $it") }
                }
            }
        }

        // --- Per-note detail list ---
        Text("每顆音明細", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TestTags.REPORT_NOTE_LIST),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(summary.notes) { note -> NoteRow(note) }
        }

        Button(
            onClick = { vm.goHome() },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.REPORT_DONE),
        ) {
            Text("回首頁")
        }
    }
}

@Composable
private fun NoteRow(note: NoteSummary) {
    val color = if (note.pitchOk) FeedbackColors.good else FeedbackColors.wrong
    Column(modifier = Modifier.testTag(TestTags.REPORT_NOTE_ROW_PREFIX + note.i)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${note.i + 1}. ${note.name}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = buildString {
                    append("分 ${note.score}")
                    note.cents?.let { append("  ${if (it >= 0) "+" else ""}${it}¢") }
                    append(if (note.pitchOk) "  ✓" else "  ✗")
                },
                color = color,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Divider()
    }
}
