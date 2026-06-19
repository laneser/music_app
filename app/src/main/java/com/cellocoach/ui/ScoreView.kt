package com.cellocoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cellocoach.core.PitchStatus
import com.cellocoach.core.ScoreNote
import kotlin.math.roundToInt

/**
 * Native Compose notation view — the in-app replacement for the browser's
 * OpenSheetMusicDisplay (OSMD).
 *
 * It draws, on a single [Canvas]:
 *  - a five-line bass-clef staff (cello music lives on the bass clef),
 *  - a crude "𝄢" clef glyph at the left,
 *  - a notehead per non-rest [ScoreNote], positioned horizontally by time and
 *    vertically by diatonic staff position (with ledger lines as needed),
 *  - a moving cursor at [currentNoteIdx] (mirrors the OSMD cursor the SSE stream
 *    advanced), and
 *  - the current note tinted by [status] — green/orange/red/red for
 *    good/close/off/wrong, matching [FeedbackColors].
 *
 * Vertical mapping uses bass-clef staff steps: the bottom staff line is G2
 * (MIDI 43, the open G string). Each diatonic step is half a line spacing.
 *
 * @param notes flattened timeline from [com.cellocoach.core.ScoreLoader].
 * @param currentNoteIdx index of the note the follower says is current (-1 = none).
 * @param status realtime [PitchStatus] of the current note, or null when idle.
 */
@Composable
fun ScoreView(
    notes: List<ScoreNote>,
    currentNoteIdx: Int,
    status: PitchStatus?,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(TestTags.PRACTICE_SCORE_VIEW),
    ) {
        drawScore(notes, currentNoteIdx, status)
    }
}

private fun DrawScope.drawScore(
    notes: List<ScoreNote>,
    currentNoteIdx: Int,
    status: PitchStatus?,
) {
    val staffColor = Color(0xFF333333)
    val noteColor = Color(0xFF222222)

    val lineSpacing = size.height / 12f          // gap between adjacent staff lines
    val staffTop = size.height / 2f - 2f * lineSpacing
    val leftPad = 56f                            // room for the clef
    val rightPad = 16f
    val usableWidth = (size.width - leftPad - rightPad).coerceAtLeast(1f)

    // --- staff: five lines ---
    for (i in 0 until 5) {
        val y = staffTop + i * lineSpacing
        drawLine(
            color = staffColor,
            start = Offset(leftPad, y),
            end = Offset(size.width - rightPad, y),
            strokeWidth = 1.5f,
        )
    }

    // --- bass clef glyph (drawn as text via the native canvas) ---
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = staffColor.toArgb()
            textSize = lineSpacing * 5f
            isAntiAlias = true
        }
        // "𝄢" may not render on all fonts; a stylised "?:" stand-in is fine for the MVP.
        drawText("𝄢", 8f, staffTop + 3.2f * lineSpacing, paint)
    }

    if (notes.isEmpty()) return

    val totalDuration = (notes.last().end).coerceAtLeast(0.001)

    // Helper: vertical centre for a MIDI note. Bottom staff line = G2 (MIDI 43).
    fun yForMidi(midi: Int): Float {
        val step = diatonicStepsAboveG2(midi)
        // bottom line is staffTop + 4*spacing; each diatonic step = half spacing up.
        return staffTop + 4f * lineSpacing - step * (lineSpacing / 2f)
    }

    notes.forEachIndexed { idx, note ->
        val cx = leftPad + (note.start / totalDuration).toFloat() * usableWidth
        if (note.isRest) {
            // small rest tick on the middle line
            val y = staffTop + 2f * lineSpacing
            drawLine(noteColor, Offset(cx, y - 4f), Offset(cx, y + 4f), strokeWidth = 2f)
            return@forEachIndexed
        }

        val cy = yForMidi(note.midi)
        val isCurrent = idx == currentNoteIdx
        val head = if (isCurrent) statusColor(status) else noteColor
        val radius = lineSpacing * 0.55f

        // ledger lines if the note sits well outside the staff
        drawLedgerLines(cx, cy, staffTop, lineSpacing, staffColor)

        drawCircle(color = head, radius = radius, center = Offset(cx, cy))
        // stem
        drawLine(
            color = head,
            start = Offset(cx + radius, cy),
            end = Offset(cx + radius, cy - lineSpacing * 3f),
            strokeWidth = 2f,
        )
    }

    // --- moving cursor on the current note ---
    if (currentNoteIdx in notes.indices) {
        val note = notes[currentNoteIdx]
        val cx = leftPad + (note.start / totalDuration).toFloat() * usableWidth
        drawRect(
            color = statusColor(status).copy(alpha = 0.22f),
            topLeft = Offset(cx - lineSpacing * 0.9f, staffTop - lineSpacing),
            size = Size(lineSpacing * 1.8f, lineSpacing * 6f),
        )
        drawLine(
            color = statusColor(status),
            start = Offset(cx, staffTop - lineSpacing),
            end = Offset(cx, staffTop + lineSpacing * 5f),
            strokeWidth = 2.5f,
        )
    }
}

/** Draw ledger lines through [cy] for notes above/below the staff. */
private fun DrawScope.drawLedgerLines(
    cx: Float,
    cy: Float,
    staffTop: Float,
    lineSpacing: Float,
    color: Color,
) {
    val bottomLine = staffTop + 4f * lineSpacing
    val half = lineSpacing / 2f
    val w = lineSpacing * 0.9f

    // below the staff
    var y = bottomLine + lineSpacing
    while (y <= cy + half) {
        drawLine(color, Offset(cx - w, y), Offset(cx + w, y), strokeWidth = 1.5f)
        y += lineSpacing
    }
    // above the staff
    y = staffTop - lineSpacing
    while (y >= cy - half) {
        drawLine(color, Offset(cx - w, y), Offset(cx + w, y), strokeWidth = 1.5f)
        y -= lineSpacing
    }
}

/** Diatonic steps (C,D,E,F,G,A,B count as 1 each) above G2 (MIDI 43). */
private fun diatonicStepsAboveG2(midi: Int): Int {
    // Map MIDI to a diatonic index, ignoring accidentals (nearest natural).
    val diatonicOfPitchClass = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6) // C..B
    val octave = midi / 12 - 1
    val pc = midi % 12
    val absoluteStep = octave * 7 + diatonicOfPitchClass[pc]
    // G2: octave 2, step index for G (pitch class 7) = 4 → 2*7 + 4 = 18
    val g2Step = 2 * 7 + 4
    return absoluteStep - g2Step
}

private fun statusColor(status: PitchStatus?): Color = when (status) {
    PitchStatus.GOOD -> FeedbackColors.good
    PitchStatus.CLOSE -> FeedbackColors.close
    PitchStatus.OFF -> FeedbackColors.off
    PitchStatus.WRONG -> FeedbackColors.wrong
    null -> FeedbackColors.idle
}

private fun Color.toArgb(): Int {
    val a = (alpha * 255).roundToInt()
    val r = (red * 255).roundToInt()
    val g = (green * 255).roundToInt()
    val b = (blue * 255).roundToInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
