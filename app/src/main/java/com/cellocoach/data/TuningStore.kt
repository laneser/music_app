package com.cellocoach.data

import com.cellocoach.core.STRING_NAMES
import com.cellocoach.core.Tuning
import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Persists per-string [Tuning] calibration across app runs.
 *
 * Port of the persistence half of `tuning.py` (`Tuning.save` / `Tuning.load` /
 * `Tuning.saved_at`). The Python version wrote to `~/.cello-practice/tuning.json`;
 * on Android we instead write `tuning.json` inside the supplied directory (the
 * caller passes the app `filesDir`). The on-disk JSON shape is identical:
 *
 * ```json
 * {
 *   "offsets": { "C": -3.2, "G": 1.5, "D": 0.0, "A": 4.1 },
 *   "saved_at": "2026-06-19T10:30:00"
 * }
 * ```
 *
 * `org.json` / Gson / Moshi are intentionally NOT used (no extra gradle deps);
 * the JSON is small and fixed-shape, so we hand-write a tiny writer/reader.
 */
class TuningStore(private val dir: File) {

    private val file: File
        get() = File(dir, FILE_NAME)

    /**
     * Load a previously saved tuning. Returns `null` if the file is missing,
     * unreadable, malformed, or does not contain all four strings — mirroring
     * `Tuning.load` in Python (which requires every string in [STRING_NAMES]).
     */
    fun load(): Tuning? {
        val f = file
        if (!f.exists()) return null
        return try {
            val obj = parseObject(f.readText())
            val offsets = parseOffsets(obj["offsets"]) ?: return null
            if (!STRING_NAMES.all { it in offsets }) return null
            Tuning(offsets.toMutableMap())
        } catch (e: Exception) {
            // OSError / ValueError equivalent: any parse or IO failure -> null.
            null
        }
    }

    /**
     * Persist the current offsets so the next run can skip calibration.
     *
     * Like Python, this is a no-op unless [Tuning.isCalibrated] is true (all four
     * strings present). The `offsets` written are rounded to 0.1 cents via
     * [Tuning.asMap], and a second-precision ISO-8601 `saved_at` timestamp is added.
     */
    fun save(tuning: Tuning) {
        if (!tuning.isCalibrated()) return
        dir.mkdirs()
        val savedAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
        file.writeText(buildJson(tuning.asMap(), savedAt))
    }

    /**
     * Return the saved-at ISO timestamp from the stored file, or `null` if there
     * is no usable file. Equivalent to `Tuning.saved_at` in Python.
     */
    fun savedAt(): String? {
        val f = file
        if (!f.exists()) return null
        return try {
            parseObject(f.readText())["saved_at"]?.let { unquote(it) }
        } catch (e: Exception) {
            null
        }
    }

    // ---- tiny JSON writer -------------------------------------------------

    private fun buildJson(offsets: Map<String, Double>, savedAt: String): String {
        val offsetEntries = offsets.entries.joinToString(",\n") { (k, v) ->
            "    ${quote(k)}: $v"
        }
        return buildString {
            append("{\n")
            append("  \"offsets\": {\n")
            append(offsetEntries)
            append("\n  },\n")
            append("  \"saved_at\": ${quote(savedAt)}\n")
            append("}\n")
        }
    }

    // ---- tiny JSON reader -------------------------------------------------
    //
    // Only handles the fixed shape we write: a flat top-level object whose values
    // are either a string or a nested flat object of string->number. That's all
    // `tuning.json` ever contains, so a full JSON parser would be overkill.

    /**
     * Parse the top-level object into a map of key -> raw value token. Nested
     * objects (i.e. "offsets") are returned as their raw `{...}` substring so the
     * caller can decide how to interpret them.
     */
    private fun parseObject(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = text.indexOf('{')
        require(i >= 0) { "not an object" }
        i++
        while (i < text.length) {
            i = skipWs(text, i)
            if (i < text.length && text[i] == '}') break
            require(text[i] == '"') { "expected key" }
            val keyEnd = text.indexOf('"', i + 1)
            require(keyEnd > i) { "unterminated key" }
            val key = text.substring(i + 1, keyEnd)
            i = skipWs(text, keyEnd + 1)
            require(i < text.length && text[i] == ':') { "expected ':'" }
            i = skipWs(text, i + 1)
            val (value, next) = readValue(text, i)
            result[key] = value
            i = skipWs(text, next)
            if (i < text.length && text[i] == ',') i++ else break
        }
        return result
    }

    /** Read a single value token (string, nested object, or number) at [start]. */
    private fun readValue(text: String, start: Int): Pair<String, Int> {
        return when (text[start]) {
            '"' -> {
                val end = text.indexOf('"', start + 1)
                require(end > start) { "unterminated string" }
                text.substring(start, end + 1) to (end + 1)
            }
            '{' -> {
                var depth = 0
                var j = start
                while (j < text.length) {
                    when (text[j]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) return text.substring(start, j + 1) to (j + 1)
                        }
                    }
                    j++
                }
                throw IllegalArgumentException("unterminated object")
            }
            else -> {
                var j = start
                while (j < text.length && text[j] !in ",}\n \t\r") j++
                text.substring(start, j) to j
            }
        }
    }

    /** Parse the nested offsets object token into string->Double. */
    private fun parseOffsets(token: String?): Map<String, Double>? {
        if (token == null) return null
        val raw = parseObject(token)
        return raw.mapValues { it.value.toDouble() }
    }

    private fun skipWs(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun unquote(token: String): String =
        if (token.length >= 2 && token.first() == '"' && token.last() == '"') {
            token.substring(1, token.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        } else {
            token
        }

    companion object {
        /** Filename inside [dir]; mirrors Python's `tuning.json`. */
        const val FILE_NAME = "tuning.json"
    }
}
