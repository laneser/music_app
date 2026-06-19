package com.cellocoach.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Result of loading a MusicXML score: a flattened single-voice [notes] timeline
 * (rests included, matching the Python reference) and the effective [bpm] that is
 * baked into each note's start/end times.
 */
data class LoadedScore(val notes: List<ScoreNote>, val bpm: Double)

/**
 * Port of `score_loader.py`.
 *
 * The Python original leans on `music21` to parse MusicXML and flatten the part
 * into a timeline. music21 is not available on Android, so this reimplements the
 * relevant slice of MusicXML parsing directly with the JDK's
 * [javax.xml.parsers.DocumentBuilder]:
 *
 *  - `.mxl` (zipped MusicXML) is detected by the "PK" ZIP magic bytes and the main
 *    score XML is pulled out via [java.util.zip] (reading `META-INF/container.xml`
 *    when present, otherwise the first non-`META-INF` `.xml`/`.musicxml` entry).
 *  - Tempo precedence mirrors the contract: `bpmOverride` → first `<sound tempo>`
 *    or `<metronome>` (beat-unit + per-minute) → 120 BPM default.
 *  - Only the first `<part>` is read. Time is accumulated measure-by-measure using
 *    the running `<divisions>` from `<attributes>`. `<note>`s advance the cursor,
 *    `<backup>` rewinds it, `<forward>` advances it, and `<chord/>` notes share the
 *    previous note's onset (the highest pitch of a chord wins) without advancing.
 */
object ScoreLoader {

    /** Semitone offset of each diatonic step within an octave (C=0 … B=11). */
    private val STEP_SEMITONE = mapOf(
        "C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11,
    )

    /** Quarter-length of each `<beat-unit>` value used by `<metronome>`. */
    private val BEAT_UNIT_QUARTERS = mapOf(
        "whole" to 4.0,
        "half" to 2.0,
        "quarter" to 1.0,
        "eighth" to 0.5,
        "16th" to 0.25,
        "32nd" to 0.125,
        "64th" to 0.0625,
    )

    private const val DEFAULT_BPM = 120.0

    /**
     * Parse [bytes] (plain MusicXML or an `.mxl` ZIP) into a [LoadedScore].
     *
     * @param bytes raw bytes; if they begin with the "PK" ZIP magic they are unzipped first.
     * @param bpmOverride if non-null, forces the tempo and overrides anything in the score.
     */
    fun load(bytes: ByteArray, bpmOverride: Double? = null): LoadedScore {
        val xmlBytes = unwrapIfZip(bytes)
        val doc = parseXml(xmlBytes)

        val bpm = bpmOverride ?: detectTempo(doc) ?: DEFAULT_BPM
        val secPerQuarter = 60.0 / bpm

        val part = firstElement(doc.documentElement, "part")
            ?: return LoadedScore(emptyList(), bpm)

        val notes = mutableListOf<ScoreNote>()
        var divisions = 1.0
        var cursorQuarters = 0.0       // current playback position, in quarter notes
        var lastOnsetQuarters = 0.0    // onset of the previous note (for <chord/>)

        for (measure in childElements(part, "measure")) {
            for (child in childElements(measure)) {
                when (child.tagName) {
                    "attributes" -> {
                        firstElement(child, "divisions")?.textContent?.trim()
                            ?.toDoubleOrNull()?.let { if (it > 0) divisions = it }
                    }

                    "backup" -> {
                        val dur = durationQuarters(child, divisions)
                        cursorQuarters -= dur
                    }

                    "forward" -> {
                        val dur = durationQuarters(child, divisions)
                        cursorQuarters += dur
                    }

                    "note" -> {
                        val isChord = firstElement(child, "chord") != null
                        val dur = durationQuarters(child, divisions)
                        val onset = if (isChord) lastOnsetQuarters else cursorQuarters
                        val start = onset * secPerQuarter
                        val end = start + dur * secPerQuarter

                        val rest = firstElement(child, "rest") != null
                        val pitchEl = firstElement(child, "pitch")

                        if (rest) {
                            // Rests stay on the timeline to match the Python reference.
                            notes.add(ScoreNote(start, end, ScoreNote.REST, "rest"))
                        } else if (pitchEl != null) {
                            val (midi, name) = pitchToMidi(pitchEl)
                            if (isChord) {
                                // Chord member shares the previous onset; keep the highest pitch.
                                val prev = notes.lastOrNull()
                                if (prev != null && !prev.isRest && midi > prev.midi) {
                                    notes[notes.lastIndex] =
                                        ScoreNote(prev.start, prev.end, midi, name)
                                } else if (prev == null || prev.isRest) {
                                    notes.add(ScoreNote(start, end, midi, name))
                                }
                            } else {
                                notes.add(ScoreNote(start, end, midi, name))
                            }
                        }

                        if (!isChord) {
                            lastOnsetQuarters = cursorQuarters
                            cursorQuarters += dur
                        }
                    }
                }
            }
        }

        return LoadedScore(notes, bpm)
    }

    // --- ZIP handling --------------------------------------------------------

    /**
     * If [bytes] is an `.mxl` ZIP (starts with "PK"), return the main score XML
     * bytes; otherwise return [bytes] unchanged.
     */
    private fun unwrapIfZip(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return bytes
        }

        // Read all entries into memory; mxl archives are small.
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Prefer the rootfile declared in META-INF/container.xml.
        entries["META-INF/container.xml"]?.let { container ->
            runCatching {
                val cdoc = parseXml(container)
                val rootfiles = cdoc.getElementsByTagName("rootfile")
                if (rootfiles.length > 0) {
                    val fullPath = (rootfiles.item(0) as Element).getAttribute("full-path")
                    if (fullPath.isNotEmpty()) entries[fullPath]?.let { return it }
                }
            }
        }

        // Fall back to the first non-META-INF .xml/.musicxml entry.
        val fallback = entries.entries.firstOrNull { (name, _) ->
            !name.startsWith("META-INF/") &&
                (name.lowercase().endsWith(".xml") || name.lowercase().endsWith(".musicxml"))
        }?.value
        return fallback
            ?: throw IllegalArgumentException("No score XML found inside compressed MusicXML")
    }

    // --- Tempo ---------------------------------------------------------------

    /** First `<sound tempo>` or `<metronome>` in document order, or null. */
    private fun detectTempo(doc: Document): Double? {
        // <sound tempo="..">
        val sounds = doc.getElementsByTagName("sound")
        for (i in 0 until sounds.length) {
            val t = (sounds.item(i) as Element).getAttribute("tempo")
            t.trim().toDoubleOrNull()?.let { if (it > 0) return it }
        }
        // <metronome><beat-unit>..</beat-unit><per-minute>..</per-minute></metronome>
        val metros = doc.getElementsByTagName("metronome")
        for (i in 0 until metros.length) {
            val m = metros.item(i) as Element
            val perMinute = firstElement(m, "per-minute")?.textContent?.trim()?.toDoubleOrNull()
            if (perMinute != null && perMinute > 0) {
                val beatUnit = firstElement(m, "beat-unit")?.textContent?.trim()
                val unitQuarters = BEAT_UNIT_QUARTERS[beatUnit] ?: 1.0
                // per-minute counts beat-units; convert to quarter-note BPM.
                return perMinute * unitQuarters
            }
        }
        return null
    }

    // --- Pitch / duration ----------------------------------------------------

    /**
     * Convert a `<pitch>` element to a (midi, name) pair.
     *
     * `midi = (octave + 1) * 12 + stepSemitone[step] + alter`. The display name is
     * `"$step$octave"`, with `#` appended for sharps and `b` for flats.
     */
    private fun pitchToMidi(pitch: Element): Pair<Int, String> {
        val step = firstElement(pitch, "step")?.textContent?.trim()?.uppercase() ?: "C"
        val octave = firstElement(pitch, "octave")?.textContent?.trim()?.toIntOrNull() ?: 4
        val alter = firstElement(pitch, "alter")?.textContent?.trim()?.toDoubleOrNull()?.toInt() ?: 0
        val semitone = STEP_SEMITONE[step] ?: 0
        val midi = (octave + 1) * 12 + semitone + alter
        val accidental = when {
            alter > 0 -> "#".repeat(alter)
            alter < 0 -> "b".repeat(-alter)
            else -> ""
        }
        return midi to "$step$accidental$octave"
    }

    /** `<duration>` of an element divided by [divisions] gives the quarter-length. */
    private fun durationQuarters(el: Element, divisions: Double): Double {
        val dur = firstElement(el, "duration")?.textContent?.trim()?.toDoubleOrNull() ?: 0.0
        return if (divisions > 0) dur / divisions else 0.0
    }

    // --- XML helpers ---------------------------------------------------------

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // MusicXML references an external DTD; never fetch it.
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/validation", false) }
        }
        val builder = factory.newDocumentBuilder().apply {
            // Swallow DTD lookups so parsing works fully offline.
            setEntityResolver { _, _ -> org.xml.sax.InputSource(ByteArrayInputStream(ByteArray(0))) }
        }
        return builder.parse(ByteArrayInputStream(bytes)).also { it.documentElement.normalize() }
    }

    /** First direct-child [Element] of [parent] named [tag], or null. */
    private fun firstElement(parent: Element, tag: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == tag) return n
        }
        return null
    }

    /** Direct-child elements of [parent] (optionally filtered by [tag]). */
    private fun childElements(parent: Element, tag: String? = null): List<Element> {
        val out = ArrayList<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) {
                val e = n as Element
                if (tag == null || e.tagName == tag) out.add(e)
            }
        }
        return out
    }
}
