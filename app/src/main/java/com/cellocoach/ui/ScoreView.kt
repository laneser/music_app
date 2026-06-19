package com.cellocoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cellocoach.core.PitchStatus
import com.cellocoach.core.ScoreNote
import kotlin.math.roundToInt

/**
 * Native Compose notation view — the in-app replacement for the browser's
 * OpenSheetMusicDisplay (OSMD).
 *
 * Design notes (v2):
 *  - Draws on its own **cream "paper" surface** with a rounded border, so the
 *    staff and noteheads stay legible regardless of the app's light/dark theme
 *    (the previous version drew near-black ink that vanished in dark mode).
 *  - Lays notes out at a **fixed horizontal spacing** and lets the staff scroll
 *    horizontally, instead of compressing the whole piece into one screen width
 *    (which crammed longer scores like `twinkle.mxl` into an unreadable blob).
 *  - **Auto-follows the cursor**: the current note is animated to the centre of
 *    the viewport, mirroring how the OSMD cursor kept the played note in view.
 *  - A small note-name label under each head helps the student read position.
 *
 * Vertical mapping uses bass-clef staff steps: the bottom staff line is G2
 * (MIDI 43, the open G string); each diatonic step is half a line spacing.
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
    val density = LocalDensity.current
    val scroll = rememberScrollState()

    val noteSpacingPx = with(density) { 48.dp.toPx() }
    val leftPadPx = with(density) { 56.dp.toPx() }
    val rightPadPx = with(density) { 28.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Paper)
            .border(1.dp, PaperBorder, RoundedCornerShape(14.dp))
            .testTag(TestTags.PRACTICE_SCORE_VIEW),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            val viewportPx = with(density) { maxWidth.toPx() }
            val contentPx = (leftPadPx + notes.size * noteSpacingPx + rightPadPx)
                .coerceAtLeast(viewportPx)
            val contentDp = with(density) { contentPx.toDp() }

            // Animate the current note to the centre of the viewport.
            LaunchedEffect(currentNoteIdx, viewportPx, notes.size) {
                if (currentNoteIdx in notes.indices) {
                    val cx = leftPadPx + currentNoteIdx * noteSpacingPx + noteSpacingPx / 2f
                    val target = (cx - viewportPx / 2f).roundToInt().coerceAtLeast(0)
                    scroll.animateScrollTo(target.coerceAtMost(scroll.maxValue))
                }
            }

            Canvas(
                modifier = Modifier
                    .width(contentDp)
                    .fillMaxHeight()
                    .horizontalScroll(scroll),
            ) {
                drawScore(notes, currentNoteIdx, status, leftPadPx, noteSpacingPx)
            }
        }
    }
}

private val Paper = Color(0xFFFBF6EA)        // warm sheet-music paper
private val PaperBorder = Color(0x33000000)
private val Ink = Color(0xFF2A2622)          // staff + idle noteheads
private val Faint = Color(0xFF8A8377)         // labels / ledger lines

private fun DrawScope.drawScore(
    notes: List<ScoreNote>,
    currentNoteIdx: Int,
    status: PitchStatus?,
    leftPad: Float,
    noteSpacing: Float,
) {
    // Staff occupies the middle band, leaving room for ledger lines + labels.
    val lineSpacing = (size.height / 13f)
    val staffTop = size.height * 0.30f
    val bottomLine = staffTop + 4f * lineSpacing
    val contentRight = size.width

    // --- five staff lines (span the whole scrollable width) ---
    for (i in 0 until 5) {
        val y = staffTop + i * lineSpacing
        drawLine(Ink, Offset(leftPad - 8f, y), Offset(contentRight - 8f, y), strokeWidth = 1.5f)
    }

    // --- bass-clef glyph ---
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = Ink.toArgb()
            textSize = lineSpacing * 4.6f
            isAntiAlias = true
        }
        drawText("𝄢", 6f, staffTop + 3.3f * lineSpacing, paint) // 𝄢
    }

    if (notes.isEmpty()) return

    fun xForIdx(idx: Int) = leftPad + idx * noteSpacing + noteSpacing / 2f
    fun yForMidi(midi: Int): Float {
        val step = diatonicStepsAboveG2(midi)
        return bottomLine - step * (lineSpacing / 2f)
    }

    // --- cursor highlight band (drawn first, behind the note) ---
    if (currentNoteIdx in notes.indices) {
        val cx = xForIdx(currentNoteIdx)
        val c = statusColor(status)
        drawRect(
            color = c.copy(alpha = 0.16f),
            topLeft = Offset(cx - noteSpacing * 0.42f, staffTop - lineSpacing * 1.6f),
            size = Size(noteSpacing * 0.84f, lineSpacing * 8f),
        )
        drawLine(c, Offset(cx, staffTop - lineSpacing * 1.6f),
            Offset(cx, bottomLine + lineSpacing * 2.4f), strokeWidth = 2.5f)
    }

    val labelPaint = android.graphics.Paint().apply {
        color = Faint.toArgb()
        textSize = lineSpacing * 1.5f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val labelPaintCurrent = android.graphics.Paint(labelPaint).apply {
        color = statusColor(status).toArgb()
        isFakeBoldText = true
    }

    notes.forEachIndexed { idx, note ->
        val cx = xForIdx(idx)
        val isCurrent = idx == currentNoteIdx
        if (note.isRest) {
            drawLine(Faint, Offset(cx, staffTop + 1.4f * lineSpacing),
                Offset(cx, staffTop + 2.6f * lineSpacing), strokeWidth = 3f)
            return@forEachIndexed
        }

        val cy = yForMidi(note.midi)
        val head = if (isCurrent) statusColor(status) else Ink
        val radius = lineSpacing * 0.58f

        drawLedgerLines(cx, cy, staffTop, bottomLine, lineSpacing, Faint)
        drawCircle(head, radius, Offset(cx, cy))
        drawLine(head, Offset(cx + radius * 0.9f, cy),
            Offset(cx + radius * 0.9f, cy - lineSpacing * 3.2f), strokeWidth = 2.2f)

        // note-name label under the staff
        drawContext.canvas.nativeCanvas.drawText(
            note.name, cx, bottomLine + lineSpacing * 2.6f,
            if (isCurrent) labelPaintCurrent else labelPaint,
        )
    }
}

private fun DrawScope.drawLedgerLines(
    cx: Float, cy: Float, staffTop: Float, bottomLine: Float, lineSpacing: Float, color: Color,
) {
    val half = lineSpacing / 2f
    val w = lineSpacing * 0.9f
    var y = bottomLine + lineSpacing
    while (y <= cy + half) { drawLine(color, Offset(cx - w, y), Offset(cx + w, y), 1.5f); y += lineSpacing }
    y = staffTop - lineSpacing
    while (y >= cy - half) { drawLine(color, Offset(cx - w, y), Offset(cx + w, y), 1.5f); y -= lineSpacing }
}

/** Diatonic steps (C,D,E,F,G,A,B count as 1 each) above G2 (MIDI 43). */
private fun diatonicStepsAboveG2(midi: Int): Int {
    val diatonicOfPitchClass = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6) // C..B
    val octave = midi / 12 - 1
    val pc = midi % 12
    val absoluteStep = octave * 7 + diatonicOfPitchClass[pc]
    val g2Step = 2 * 7 + 4
    return absoluteStep - g2Step
}

private fun statusColor(status: PitchStatus?): Color = when (status) {
    PitchStatus.GOOD -> FeedbackColors.good
    PitchStatus.CLOSE -> FeedbackColors.close
    PitchStatus.OFF -> FeedbackColors.off
    PitchStatus.WRONG -> FeedbackColors.wrong
    null -> Ink
}

private fun Color.toArgb(): Int {
    val a = (alpha * 255).roundToInt()
    val r = (red * 255).roundToInt()
    val g = (green * 255).roundToInt()
    val b = (blue * 255).roundToInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
