package com.cellocoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportHtmlTest {

    private fun summary() = PracticeSummary(
        score = 87.5,
        nTotal = 3,
        nCorrect = 2,
        meanCents = 6.0,
        duration = 4.0,
        notes = listOf(
            NoteSummary(0, "G2", 43, 43, 95.0, true, 4.0, 0.9),
            NoteSummary(1, "A2", 45, 46, 40.0, false, null, 0.2),
            NoteSummary(2, "B2", 47, 47, 88.0, true, 22.0, 0.8),
        ),
        diagnostics = listOf("整體音準偏高 6¢"),
    )

    @Test
    fun `html embeds the headline numbers and every note`() {
        val html = ReportHtml.build(summary(), "g_major_scale.musicxml", "2026-06-19 21:00")
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("87.5"))           // score
        assertTrue(html.contains("2 / 3"))          // n correct / total
        assertTrue(html.contains("g_major_scale.musicxml"))
        assertTrue(html.contains("整體音準偏高 6¢")) // diagnostic
        // each expected note name appears
        listOf("G2", "A2", "B2").forEach { assertTrue("missing $it", html.contains(it)) }
        assertTrue(html.contains("<table"))
    }

    @Test
    fun `no diagnostics renders the all-clear line`() {
        val s = summary().copy(diagnostics = emptyList())
        val html = ReportHtml.build(s, "x.musicxml", "t")
        assertTrue(html.contains("沒有發現明顯問題"))
    }

    @Test
    fun `html escapes special characters in the score name`() {
        val html = ReportHtml.build(summary(), "a<b>&\"c", "t")
        assertTrue(html.contains("a&lt;b&gt;&amp;&quot;c"))
    }

    @Test
    fun `midiName maps numbers to note names`() {
        assertEquals("A3", ReportHtml.midiName(57))
        assertEquals("C2", ReportHtml.midiName(36))
        assertEquals("A4", ReportHtml.midiName(69))
    }
}
