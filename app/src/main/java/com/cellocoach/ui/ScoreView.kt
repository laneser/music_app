package com.cellocoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * Design (v3 — "teleprompter"):
 *  - The **staff and bass clef are fixed**; the **notes scroll** underneath, so
 *    the current note stays pinned to the centre of the view and the music
 *    flows past it (instead of the cursor crawling to the right edge).
 *  - Half-a-viewport of blank lead/trail padding lets even the first and last
 *    notes reach the centre.
 *  - Cream "paper" surface so the ink is legible in light *and* dark themes.
 *  - A note-name label under each head; the current note + its label are tinted
 *    by [status] (green/amber/orange/red for good/close/off/wrong).
 *
 * Vertical mapping: bottom staff line = G2 (MIDI 43, open G string); each
 * diatonic step is half a line spacing.
 */
@Composable
fun ScoreView(
    notes: List<ScoreNote>,
    currentNoteIdx: Int,
    status: PitchStatus?,
    bpm: Double,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()

    // Auto-pick the clef from the score's register so the notes land on/near the
    // staff instead of floating off the top (e.g. a treble-range piano melody on
    // a bass staff). refMidi is the MIDI of the bottom staff line.
    val refMidi = remember(notes) { staffReferenceMidi(notes) }
    val clefGlyph = if (refMidi >= TREBLE_BOTTOM) "𝄞" else "𝄢" // 𝄞 / 𝄢

    val noteSpacingPx = with(density) { 52.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Paper)
            .border(1.dp, PaperBorder, RoundedCornerShape(14.dp))
            .testTag(TestTags.PRACTICE_SCORE_VIEW),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewportPx = constraints.maxWidth.toFloat()
            // Blank padding so the first/last note can still sit in the centre.
            val leadPx = viewportPx / 2f
            val trailPx = viewportPx / 2f
            fun noteX(i: Int) = leadPx + i * noteSpacingPx + noteSpacingPx / 2f

            val contentPx = (leadPx + notes.size * noteSpacingPx + trailPx)
                .coerceAtLeast(viewportPx)
            val contentDp = with(density) { contentPx.toDp() }

            // Keep the current note centred: scroll so its x lands at viewport/2.
            LaunchedEffect(currentNoteIdx, viewportPx, notes.size) {
                if (currentNoteIdx in notes.indices) {
                    val target = (noteX(currentNoteIdx) - viewportPx / 2f).roundToInt()
                    scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
                }
            }

            // Fixed layer: staff lines + clef + the centred cursor guide.
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawStaffAndCursor(status, currentNoteIdx in notes.indices, clefGlyph)
            }

            // Scrolling layer: the notes themselves.
            Row(modifier = Modifier.fillMaxSize().horizontalScroll(scroll)) {
                Canvas(modifier = Modifier.width(contentDp).fillMaxHeight()) {
                    drawNotes(notes, currentNoteIdx, status, leadPx, noteSpacingPx, refMidi, bpm)
                }
            }
        }
    }
}

private val Paper = Color(0xFFFBF6EA)        // warm sheet-music paper
private val PaperBorder = Color(0x33000000)
private val Ink = Color(0xFF2A2622)          // staff + idle noteheads
private val Faint = Color(0xFF8A8377)         // labels / ledger lines

/** Geometry shared by both layers so notes line up with the fixed staff. */
private fun DrawScope.lineSpacing() = size.height / 13f
private fun DrawScope.staffTop() = size.height * 0.30f

/** Fixed layer: five staff lines, a pinned clef, and the centre cursor guide. */
private fun DrawScope.drawStaffAndCursor(status: PitchStatus?, hasCurrent: Boolean, clefGlyph: String) {
    val ls = lineSpacing()
    val top = staffTop()
    val bottom = top + 4f * ls

    for (i in 0 until 5) {
        val y = top + i * ls
        drawLine(Ink, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
    }
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = Ink.toArgb(); textSize = ls * 4.6f; isAntiAlias = true
        }
        drawText(clefGlyph, 8f, top + 3.3f * ls, paint)
    }

    // Centre cursor — the note being judged is scrolled to here.
    if (hasCurrent) {
        val cx = size.width / 2f
        val c = statusColor(status)
        drawRect(
            color = c.copy(alpha = 0.16f),
            topLeft = Offset(cx - ls * 1.3f, top - ls * 1.6f),
            size = Size(ls * 2.6f, ls * 8f),
        )
        drawLine(c, Offset(cx, top - ls * 1.6f), Offset(cx, bottom + ls * 2.4f), strokeWidth = 2.5f)
    }
}

/** Scrolling layer: noteheads, stems, ledger lines, and note-name labels. */
private fun DrawScope.drawNotes(
    notes: List<ScoreNote>,
    currentNoteIdx: Int,
    status: PitchStatus?,
    leadPx: Float,
    noteSpacing: Float,
    refMidi: Int,
    bpm: Double,
) {
    if (notes.isEmpty()) return
    val ls = lineSpacing()
    val top = staffTop()
    val bottom = top + 4f * ls
    val refStep = absoluteDiatonicStep(refMidi)
    val secPerQuarter = 60.0 / bpm.coerceAtLeast(1.0)

    fun xForIdx(i: Int) = leadPx + i * noteSpacing + noteSpacing / 2f
    // Bottom staff line = refMidi; each diatonic step is half a line spacing up.
    fun yForMidi(midi: Int): Float = bottom - (absoluteDiatonicStep(midi) - refStep) * (ls / 2f)

    val labelPaint = android.graphics.Paint().apply {
        color = Faint.toArgb(); textSize = ls * 1.5f; isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val labelCurrent = android.graphics.Paint(labelPaint).apply {
        color = statusColor(status).toArgb(); isFakeBoldText = true
    }

    val n = notes.size
    val shapes = Array(n) { classifyDuration((notes[it].end - notes[it].start) / secPerQuarter) }
    val stemLen = ls * 3.2f

    // --- beam grouping: maximal runs of consecutive pitched notes that carry a
    // flag (eighth or shorter) and fall within the same quarter-note beat. ---
    fun beat(i: Int) = kotlin.math.floor(notes[i].start / secPerQuarter + 1e-6).toInt()
    fun beamable(i: Int) = !notes[i].isRest && shapes[i].flags >= 1
    val groupOf = IntArray(n) { -1 }
    val groups = ArrayList<IntRange>()
    run {
        var i = 0
        while (i < n) {
            if (beamable(i)) {
                var j = i
                while (j + 1 < n && beamable(j + 1) && beat(j + 1) == beat(i)) j++
                if (j > i) { groups.add(i..j); for (k in i..j) groupOf[k] = groups.size - 1 }
                i = j + 1
            } else i++
        }
    }

    // --- heads, labels, rests, and stems/flags for UN-beamed notes ---
    notes.forEachIndexed { idx, note ->
        val cx = xForIdx(idx)
        val isCurrent = idx == currentNoteIdx
        val shape = shapes[idx]
        val ink = if (isCurrent) statusColor(status) else Ink
        if (note.isRest) {
            drawRest(cx, top, ls, shape, Faint)
            return@forEachIndexed
        }
        val cy = yForMidi(note.midi)
        drawLedgerLines(cx, cy, top, bottom, ls, Faint)
        drawNoteHead(cx, cy, ink, ls, shape.filled, shape.dotted)
        if (groupOf[idx] < 0 && shape.stem) {
            drawStem(cx, cy, ls, ink, cy - stemLen)
            drawFlags(cx, ls, ink, cy - stemLen, shape.flags)
        }
        drawContext.canvas.nativeCanvas.drawText(
            note.name, cx, bottom + ls * 2.6f, if (isCurrent) labelCurrent else labelPaint,
        )
    }

    // --- beams: stems of each group reach a shared beam line, joined by 1 (eighth)
    // or 2 (sixteenth) horizontal beams. ---
    val beamThick = ls * 0.5f
    val beamGap = ls * 0.62f
    for (g in groups) {
        val idxs = g.toList()
        val beamY = idxs.minOf { yForMidi(notes[it].midi) } - stemLen
        idxs.forEach { k ->
            val ink = if (k == currentNoteIdx) statusColor(status) else Ink
            drawStem(xForIdx(k), yForMidi(notes[k].midi), ls, ink, beamY)
        }
        // primary beam across the whole group
        drawLine(Ink, Offset(stemX(xForIdx(idxs.first()), ls), beamY),
            Offset(stemX(xForIdx(idxs.last()), ls), beamY), strokeWidth = beamThick)
        // secondary beam between adjacent sixteenth notes
        for (a in 0 until idxs.size - 1) {
            if (shapes[idxs[a]].flags >= 2 && shapes[idxs[a + 1]].flags >= 2) {
                drawLine(Ink, Offset(stemX(xForIdx(idxs[a]), ls), beamY + beamGap),
                    Offset(stemX(xForIdx(idxs[a + 1]), ls), beamY + beamGap), strokeWidth = beamThick)
            }
        }
    }
}

private fun stemX(cx: Float, ls: Float) = cx + (ls * 0.62f) * 0.92f

/** Notehead/stem/flags/dot for one note value. */
private data class NoteShape(val filled: Boolean, val stem: Boolean, val flags: Int, val dotted: Boolean)

/** Classify a quarter-length into the nearest standard note value (log distance). */
private fun classifyDuration(ql: Double): NoteShape {
    val templates = listOf(
        4.0 to NoteShape(filled = false, stem = false, flags = 0, dotted = false), // whole
        3.0 to NoteShape(filled = false, stem = true, flags = 0, dotted = true),   // dotted half
        2.0 to NoteShape(filled = false, stem = true, flags = 0, dotted = false),  // half
        1.5 to NoteShape(filled = true, stem = true, flags = 0, dotted = true),    // dotted quarter
        1.0 to NoteShape(filled = true, stem = true, flags = 0, dotted = false),   // quarter
        0.75 to NoteShape(filled = true, stem = true, flags = 1, dotted = true),   // dotted eighth
        0.5 to NoteShape(filled = true, stem = true, flags = 1, dotted = false),   // eighth
        0.375 to NoteShape(filled = true, stem = true, flags = 2, dotted = true),  // dotted 16th
        0.25 to NoteShape(filled = true, stem = true, flags = 2, dotted = false),  // sixteenth
    )
    val q = ql.coerceAtLeast(0.0625)
    return templates.minByOrNull { kotlin.math.abs(kotlin.math.ln(q / it.first)) }!!.second
}

private fun DrawScope.drawNoteHead(cx: Float, cy: Float, color: Color, ls: Float, filled: Boolean, dotted: Boolean) {
    val rx = ls * 0.62f
    val ry = ls * 0.5f
    if (filled) {
        drawOval(color, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2))
    } else {
        drawOval(color, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2),
            style = Stroke(width = ls * 0.18f))
    }
    if (dotted) drawCircle(color, ls * 0.16f, Offset(cx + rx + ls * 0.45f, cy))
}

private fun DrawScope.drawStem(cx: Float, cy: Float, ls: Float, color: Color, topY: Float) {
    drawLine(color, Offset(stemX(cx, ls), cy), Offset(stemX(cx, ls), topY), strokeWidth = ls * 0.16f)
}

/** Individual flags, used only for un-beamed eighth/sixteenth notes. */
private fun DrawScope.drawFlags(cx: Float, ls: Float, color: Color, stemTop: Float, flags: Int) {
    val sx = stemX(cx, ls)
    for (k in 0 until flags) {
        val fy = stemTop + k * ls * 0.7f
        drawLine(color, Offset(sx, fy), Offset(sx + ls * 0.9f, fy + ls * 0.95f), strokeWidth = ls * 0.16f)
    }
}

/** A simple duration-aware rest mark. */
private fun DrawScope.drawRest(cx: Float, top: Float, ls: Float, shape: NoteShape, color: Color) {
    val midY = top + 2f * ls
    if (!shape.filled && !shape.stem) {
        // whole rest — hanging block under the 2nd line from top
        drawRect(color, topLeft = Offset(cx - ls * 0.5f, top + ls - ls * 0.25f), size = Size(ls, ls * 0.25f))
    } else {
        // generic rest: a short zigzag-ish bar centred on the middle line
        drawRect(color, topLeft = Offset(cx - ls * 0.18f, midY - ls * 0.8f), size = Size(ls * 0.36f, ls * 1.6f))
    }
    if (shape.dotted) drawCircle(color, ls * 0.16f, Offset(cx + ls * 0.6f, midY))
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

/** Standard treble-clef bottom line = E4 (MIDI 64); bass-clef bottom line = G2 (43). */
private const val TREBLE_BOTTOM = 64
private const val BASS_BOTTOM = 43

/** Absolute diatonic step index (C,D,E,F,G,A,B = 1 each), ignoring accidentals. */
private fun absoluteDiatonicStep(midi: Int): Int {
    val diatonicOfPitchClass = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6) // C..B
    val octave = midi / 12 - 1
    val pc = ((midi % 12) + 12) % 12
    return octave * 7 + diatonicOfPitchClass[pc]
}

/**
 * Pick the staff's bottom-line MIDI from the score's register: treble (E4) when
 * the median note is C4 or higher, otherwise bass (G2). Keeps the notes on/near
 * the staff regardless of whether it's cello-range or an imported treble part.
 */
private fun staffReferenceMidi(notes: List<ScoreNote>): Int {
    val pitches = notes.filter { !it.isRest }.map { it.midi }.sorted()
    if (pitches.isEmpty()) return BASS_BOTTOM
    val median = pitches[pitches.size / 2]
    return if (median >= 60) TREBLE_BOTTOM else BASS_BOTTOM
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
