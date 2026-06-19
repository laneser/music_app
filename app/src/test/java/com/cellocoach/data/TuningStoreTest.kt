package com.cellocoach.data

import com.cellocoach.core.STRING_NAMES
import com.cellocoach.core.Tuning
import com.cellocoach.core.nominalHzForString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM unit tests for [TuningStore] — the Android equivalent of `tuning.py`'s
 * save/load/saved_at round-trip. Uses a [TemporaryFolder] so no app context or
 * Robolectric is needed.
 */
class TuningStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Build a fully calibrated tuning with deterministic offsets. */
    private fun calibratedTuning(): Tuning {
        val tuning = Tuning()
        // Calibrate each string slightly sharp/flat of nominal so offsets are non-zero.
        for ((idx, name) in STRING_NAMES.withIndex()) {
            val nominal = nominalHzForString(name)
            // A small, distinct Hz perturbation per string.
            val detected = nominal * (1.0 + 0.001 * (idx + 1))
            tuning.calibrateString(name, detected)
        }
        return tuning
    }

    @Test
    fun saveThenLoadRoundTripsEqual() {
        val store = TuningStore(tmp.root)
        val original = calibratedTuning()

        store.save(original)
        val loaded = store.load()

        assertNotNull("load() must return a Tuning after save()", loaded)
        // Offsets are persisted rounded to 0.1 (asMap), so compare against asMap.
        assertEquals(original.asMap(), loaded!!.asMap())
        assertTrue("loaded tuning should be calibrated", loaded.isCalibrated())
    }

    @Test
    fun savedAtIsNonNullAfterSave() {
        val store = TuningStore(tmp.root)
        assertNull("no file yet -> savedAt null", store.savedAt())

        store.save(calibratedTuning())

        val ts = store.savedAt()
        assertNotNull("savedAt() must be non-null after save()", ts)
        assertTrue("timestamp should look ISO-8601", ts!!.contains("T"))
    }

    @Test
    fun loadReturnsNullWhenFileMissing() {
        val store = TuningStore(tmp.root)
        assertNull(store.load())
        assertNull(store.savedAt())
    }

    @Test
    fun saveIsNoOpWhenNotCalibrated() {
        val store = TuningStore(tmp.root)
        val partial = Tuning()
        partial.calibrateString(STRING_NAMES.first(), nominalHzForString(STRING_NAMES.first()))

        store.save(partial)

        assertNull("uncalibrated tuning must not be persisted", store.load())
        assertNull(store.savedAt())
    }

    @Test
    fun loadReturnsNullWhenOffsetsIncomplete() {
        // Write a file that is valid JSON but is missing one string.
        val incomplete = StringBuilder("{\n  \"offsets\": {\n")
        val missing = STRING_NAMES.dropLast(1)
        incomplete.append(missing.joinToString(",\n") { "    \"$it\": 1.0" })
        incomplete.append("\n  },\n  \"saved_at\": \"2026-06-19T10:00:00\"\n}\n")
        tmp.newFile(TuningStore.FILE_NAME).writeText(incomplete.toString())

        val store = TuningStore(tmp.root)
        assertNull("incomplete offsets must yield null", store.load())
    }

    @Test
    fun loadReturnsNullWhenFileCorrupt() {
        tmp.newFile(TuningStore.FILE_NAME).writeText("this is not json {{{")
        val store = TuningStore(tmp.root)
        assertNull("corrupt file must yield null", store.load())
    }
}
