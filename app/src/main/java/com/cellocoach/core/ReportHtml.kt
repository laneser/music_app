package com.cellocoach.core

/**
 * Renders a [PracticeSummary] as a single self-contained HTML document a student
 * can hand to a teacher. Pure string building (no Android deps) so it is unit
 * testable; the UI layer writes the result to a file and shares it.
 *
 * The page has: an overall scoreboard, the auto-diagnostics, and a per-note
 * table colour-coded by whether each note was hit — exactly the information the
 * in-app report shows, but portable and printable.
 */
object ReportHtml {

    /** Build the HTML. [generatedAt] is an ISO-ish timestamp supplied by the caller. */
    fun build(summary: PracticeSummary, scoreName: String, generatedAt: String): String {
        val mean = summary.meanCents
        val meanText = if (mean != null) "${if (mean >= 0) "+" else ""}$mean¢" else "—"
        val rows = summary.notes.joinToString("\n") { n -> noteRow(n) }
        val diags = if (summary.diagnostics.isEmpty()) {
            "<li class=\"ok\">沒有發現明顯問題，繼續保持！</li>"
        } else {
            summary.diagnostics.joinToString("\n") { "<li>${esc(it)}</li>" }
        }
        return """
<!DOCTYPE html>
<html lang="zh-Hant">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>大提琴練習報告 — ${esc(scoreName)}</title>
<style>
  :root { --good:#43a047; --close:#f9a825; --off:#ef6c00; --wrong:#e53935; }
  body { font-family: system-ui, "Noto Sans CJK TC", sans-serif; margin: 24px; color:#222; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  .meta { color:#666; font-size: 13px; margin-bottom: 16px; }
  .cards { display:flex; flex-wrap:wrap; gap:12px; margin-bottom:20px; }
  .card { border:1px solid #ddd; border-radius:10px; padding:12px 16px; min-width:120px; }
  .card .v { font-size:26px; font-weight:700; }
  .card .l { color:#666; font-size:12px; }
  ul.diag { background:#fff8e1; border:1px solid #ffe082; border-radius:10px; padding:12px 16px 12px 32px; }
  ul.diag li.ok { list-style:none; margin-left:-16px; color:#2e7d32; }
  table { border-collapse: collapse; width:100%; font-size:14px; }
  th, td { border-bottom:1px solid #eee; padding:6px 8px; text-align:left; }
  th { color:#666; font-weight:600; }
  td.num { text-align:right; font-variant-numeric: tabular-nums; }
  .pill { display:inline-block; padding:2px 8px; border-radius:10px; color:#fff; font-size:12px; }
  .ok-row td { background:#f6fbf6; }
  .bad-row td { background:#fdf3f3; }
  footer { margin-top:24px; color:#999; font-size:12px; }
</style>
</head>
<body>
  <h1>大提琴練習報告</h1>
  <div class="meta">曲目：${esc(scoreName)} ・ 產生時間：${esc(generatedAt)}</div>

  <div class="cards">
    <div class="card"><div class="v">${summary.score}</div><div class="l">總分 / 100</div></div>
    <div class="card"><div class="v">${summary.nCorrect} / ${summary.nTotal}</div><div class="l">對音</div></div>
    <div class="card"><div class="v">$meanText</div><div class="l">平均音準偏移</div></div>
    <div class="card"><div class="v">${summary.duration}s</div><div class="l">長度</div></div>
  </div>

  <h2 style="font-size:16px;">診斷</h2>
  <ul class="diag">
$diags
  </ul>

  <h2 style="font-size:16px;">每顆音明細</h2>
  <table>
    <thead><tr>
      <th>#</th><th>應拉</th><th>你拉</th><th class="num">音準(¢)</th>
      <th class="num">穩定度</th><th class="num">分數</th><th>狀態</th>
    </tr></thead>
    <tbody>
$rows
    </tbody>
  </table>

  <footer>由「大提琴練習助手」產生 — 老師可依「最低分」「反覆錯音」與單弦偏移判讀問題。</footer>
</body>
</html>
""".trim()
    }

    private fun noteRow(n: NoteSummary): String {
        val played = n.playedMidi?.let { midiName(it) } ?: "—"
        val cents = n.cents?.let { "${if (it >= 0) "+" else ""}$it" } ?: "—"
        val (label, color) = statusLabel(n)
        val rowClass = if (n.pitchOk) "ok-row" else "bad-row"
        return """      <tr class="$rowClass">
        <td class="num">${n.i + 1}</td>
        <td>${esc(n.name)}</td>
        <td>${esc(played)}</td>
        <td class="num">$cents</td>
        <td class="num">${n.sustain}</td>
        <td class="num">${n.score}</td>
        <td><span class="pill" style="background:$color">$label</span></td>
      </tr>"""
    }

    /** Teacher-facing status from the per-note metrics (mirrors the realtime chip). */
    private fun statusLabel(n: NoteSummary): Pair<String, String> = when {
        !n.pitchOk -> "錯/偏" to "var(--wrong)"
        (n.cents?.let { kotlin.math.abs(it) } ?: 0.0) < 20 -> "準" to "var(--good)"
        (n.cents?.let { kotlin.math.abs(it) } ?: 0.0) < 50 -> "接近" to "var(--close)"
        else -> "偏離" to "var(--off)"
    }

    /** MIDI number → note name, e.g. 57 -> "A3". */
    fun midiName(midi: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val octave = midi / 12 - 1
        return names[((midi % 12) + 12) % 12] + octave
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}
