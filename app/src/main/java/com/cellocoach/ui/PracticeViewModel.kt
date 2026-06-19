package com.cellocoach.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cellocoach.audio.AudioPitchSource
import com.cellocoach.core.Clock
import com.cellocoach.core.PitchFrame
import com.cellocoach.core.PitchSource
import com.cellocoach.core.PitchStatus
import com.cellocoach.core.ScoreFollower
import com.cellocoach.core.ScoreLoader
import com.cellocoach.core.ScoreNote
import com.cellocoach.core.Scorer
import com.cellocoach.core.SystemClock
import com.cellocoach.core.Tuning
import com.cellocoach.core.PracticeSummary
import com.cellocoach.core.nominalHzForString
import com.cellocoach.core.pitchStatus
import com.cellocoach.core.ReportHtml
import com.cellocoach.audio.Metronome
import com.cellocoach.data.ScoreLibrary
import com.cellocoach.data.TuningStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

/** Which top-level screen [CelloCoachApp] is showing. */
enum class Screen { HOME, TUNING, PRACTICE, REPORT }

/**
 * The single ViewModel behind the whole UI.
 *
 * It is the Android port of the Flask server's global `state` dict plus the two
 * background threads in `main.py`:
 *
 *  - [practiceTick] mirrors `_practice_tick_loop` — read the latest pitch, feed
 *    the [ScoreFollower] (cursor advance) and the [Scorer] (per-note buckets).
 *  - [calibrationTick] mirrors `_calibration_tick_loop` — accumulate stable
 *    open-string readings until [CALIB_REQUIRED_TICKS], store the offset, then
 *    advance to the next string and persist via [TuningStore].
 *
 * In place of `time.sleep(0.05)` background threads we run two coroutines on
 * [viewModelScope] that `delay(TICK_MS)` (50 ms). In place of the SSE payload we
 * expose Compose [androidx.compose.runtime.State] fields the screens read
 * directly, so a recomposition happens whenever the engine state changes.
 *
 * A [PitchSource] is injected (real [AudioPitchSource] in the app, a
 * [com.cellocoach.audio.FakePitchSource] in tests). The latest frame it produces
 * is stashed in [latestFrame]; the ticks read it, exactly like `detector.get()`
 * read the last value POSTed to `/pitch`.
 */
class PracticeViewModel(
    app: Application,
    private val pitchSource: PitchSource,
    private val tuningStore: TuningStore = TuningStore(app.filesDir),
    private val scoreLibrary: ScoreLibrary = ScoreLibrary(File(app.filesDir, "scores")),
    /**
     * Clock handed to every [ScoreFollower] this ViewModel builds. Production uses
     * the real [SystemClock]; Robolectric UI tests inject a controllable clock so
     * the time-based parts of `score_follower.py` (the hard timeout that finishes
     * the final note) fire deterministically instead of depending on wall-clock
     * elapsed time, which the Compose test clock does not move. This is the only
     * additive test hook on the UI layer — it changes nothing about production
     * behaviour because the default is exactly the previous hardcoded clock.
     */
    private val clock: Clock = SystemClock,
    /**
     * Whether the ViewModel drives its own loops with `viewModelScope` coroutines
     * (`delay(TICK_MS)` tick loop + metronome countdown). Production leaves this
     * `true`. Robolectric tests set it `false` because the Compose test main clock
     * does **not** drive `kotlinx.coroutines.delay` — it only drives the frame
     * clock. In that mode the tick loop and the countdown coroutine are not
     * started; tests step the engine deterministically via [stepForTest] and the
     * countdown is collapsed (the score starts immediately). This is purely a test
     * seam; with the default it changes nothing about production behaviour.
     */
    private val autoDrive: Boolean = true,
) : AndroidViewModel(app) {

    // ---- Navigation --------------------------------------------------------
    var screen by mutableStateOf(Screen.HOME)
        private set

    // ---- Score selection ---------------------------------------------------
    /** Bundled assets + user-imported scores (the picker's contents). */
    var availableScores by mutableStateOf(listAssetScores() + scoreLibrary.list())
        private set

    var selectedScore by mutableStateOf(availableScores.firstOrNull() ?: DEFAULT_SCORE)
        private set

    var bpm by mutableStateOf(120.0)
        private set

    /** Practice tempo multiplier: 1.0 = score tempo, 0.75/0.5 = slower practice. */
    var tempoFactor by mutableStateOf(1.0)
        private set

    /** Whether the metronome makes sound (count-in + on-beat). Off by default to
     *  avoid the speaker click bleeding into the mic — headphones recommended. */
    var metronomeOn by mutableStateOf(false)
        private set

    /** Transient status line for import (downloading / done / error). */
    var importStatus by mutableStateOf<String?>(null)
        private set

    private var notes: List<ScoreNote> = emptyList()
    val totalNotes: Int get() = notes.size

    // ---- Engine ------------------------------------------------------------
    private var follower: ScoreFollower? = null
    private var scorer: Scorer? = null
    var tuning by mutableStateOf(Tuning())
        private set

    /** Most recent pitch reading from the source (port of `detector.get()`). */
    @Volatile private var latestFrame: PitchFrame = PitchFrame(null, 0f)

    // ---- Tuning / calibration state ---------------------------------------
    /** True once a saved 4-string tuning exists. */
    var hasSavedTuning by mutableStateOf(false)
        private set
    var savedTuningAt by mutableStateOf<String?>(null)
        private set

    var calibTarget by mutableStateOf<String?>("C")
        private set
    var calibProgress by mutableStateOf(0f)
        private set
    var calibCents by mutableStateOf<Double?>(null)
        private set
    private val calibBuffer = mutableListOf<Float>()

    // ---- Practice realtime state ------------------------------------------
    var started by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set
    var currentNoteIdx by mutableStateOf(-1)
        private set
    var expectedName by mutableStateOf<String?>(null)
        private set
    var expectedHz by mutableStateOf<Double?>(null)
        private set
    var expectedIsRest by mutableStateOf(false)
        private set
    var detectedHz by mutableStateOf<Double?>(null)
        private set
    var detectedMidi by mutableStateOf<Int?>(null)
        private set
    var cents by mutableStateOf<Double?>(null)
        private set
    var status by mutableStateOf<PitchStatus?>(null)
        private set

    /** Metronome countdown: 4..1 before the score starts, 0 = running. */
    var countdown by mutableStateOf(0)
        private set

    // ---- Report ------------------------------------------------------------
    var summary by mutableStateOf<PracticeSummary?>(null)
        private set

    /** Notes exposed read-only so [ScoreView] can lay out the staff. */
    val scoreNotes: List<ScoreNote> get() = notes

    private var tickJob: Job? = null
    private var countdownJob: Job? = null
    private var beatJob: Job? = null
    private var sourceStarted = false
    private val metronome = Metronome()

    /** Note timeline handed to the engine, stretched for slow practice so the
     *  follower's time-based fallbacks scale with the chosen tempo. Display
     *  ([scoreNotes]) stays unscaled — only timing changes. */
    private fun engineNotes(): List<ScoreNote> =
        if (tempoFactor >= 0.999) notes
        else notes.map { it.copy(start = it.start / tempoFactor, end = it.end / tempoFactor) }

    /** Effective metronome beat at the current practice tempo. */
    private fun beatMs(): Long = (60_000.0 / (bpm * tempoFactor)).toLong().coerceAtLeast(120L)

    init {
        loadSavedTuning()
        loadScore(selectedScore)
    }

    // =======================================================================
    // Public actions (called from the screens)
    // =======================================================================

    fun selectScore(name: String) {
        if (name == selectedScore) return
        selectedScore = name
        loadScore(name)
    }

    /** Home → either Tuning (if not yet calibrated) or straight to Practice. */
    fun onStartPressed() {
        ensureSourceRunning()
        if (tuning.isCalibrated()) {
            goToPractice()
        } else {
            beginCalibration()
        }
    }

    /** Force (re)calibration from Home, even if a saved tuning exists. */
    fun beginCalibration() {
        ensureSourceRunning()
        tuning.clear()
        tuning = Tuning(tuning.offsets) // trigger recomposition with cleared map
        calibTarget = "C"
        calibProgress = 0f
        calibCents = null
        calibBuffer.clear()
        screen = Screen.TUNING
        startTicking()
    }

    /** Skip calibration — cents computed against A=440 (port of `/tune/skip`). */
    fun skipCalibration() {
        tuning.clear()
        tuning = Tuning(tuning.offsets)
        calibTarget = null
        calibBuffer.clear()
        goToPractice()
    }

    /** Enter the practice screen and start the metronome countdown. */
    fun goToPractice() {
        ensureSourceRunning()
        resetEngine()
        screen = Screen.PRACTICE
        startTicking()
        startCountdown()
    }

    /** Restart the current score from the top (port of `/reset`). */
    fun resetPractice() {
        countdownJob?.cancel()
        resetEngine()
        startCountdown()
    }

    fun goHome() {
        countdownJob?.cancel()
        beatJob?.cancel()
        screen = Screen.HOME
    }

    /** Set the practice tempo multiplier (e.g. 1.0, 0.75, 0.5). */
    fun setPracticeTempo(factor: Double) {
        tempoFactor = factor.coerceIn(0.25, 1.0)
        resetEngine() // rebuild follower with the rescaled timeline
    }

    /** Toggle whether the metronome makes sound. */
    fun toggleMetronome() { metronomeOn = !metronomeOn }

    // =======================================================================
    // Source wiring
    // =======================================================================

    private fun ensureSourceRunning() {
        if (sourceStarted) return
        sourceStarted = true
        pitchSource.start { frame -> latestFrame = frame }
    }

    private fun startTicking() {
        if (!autoDrive) return // tests drive the engine via stepForTest()
        if (tickJob?.isActive == true) return
        tickJob = viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                when (screen) {
                    Screen.TUNING -> calibrationTick()
                    Screen.PRACTICE -> practiceTick()
                    else -> { /* idle */ }
                }
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        if (!autoDrive) {
            // Tests can't drive coroutine delays via the Compose clock, so collapse
            // the lead-in: start the follower immediately.
            countdown = 0
            follower?.start()
            started = true
            refreshPracticeState() // reflect note 0 on the cursor immediately
            return
        }
        beatJob?.cancel()
        countdownJob = viewModelScope.launch {
            // 4-beat metronome lead-in at the (possibly slowed) tempo.
            val beat = beatMs()
            for (b in 4 downTo 1) {
                countdown = b
                if (metronomeOn) { if (b == 4) metronome.accent() else metronome.tick() }
                delay(beat)
            }
            countdown = 0
            follower?.start()
            started = true
            refreshPracticeState() // reflect note 0 on the cursor immediately
            if (metronomeOn) startMetronomeBeats()
        }
    }

    /** Ongoing on-beat metronome during practice (only while [metronomeOn]). */
    private fun startMetronomeBeats() {
        beatJob?.cancel()
        beatJob = viewModelScope.launch {
            val beat = beatMs()
            while (started && !done) {
                metronome.tick()
                delay(beat)
            }
        }
    }

    // =======================================================================
    // Tick loops (ports of main.py)
    // =======================================================================

    /** Port of `_practice_tick_loop`. */
    private fun practiceTick() {
        val f = follower ?: return
        val s = scorer ?: return
        if (!f.started() || f.isDone()) {
            refreshPracticeState()
            return
        }
        val frame = latestFrame
        val hz = frame.hz?.takeIf { it > 0f }
        val detMidi = hz?.let { (69.0 + 12.0 * log2(it / 440.0)).roundToInt() }
        f.observe(detMidi)
        s.observe(f.currentNoteIdx(), hz)
        refreshPracticeState()
    }

    /** Recompute all the realtime Compose state from the engine (port of the SSE payload build). */
    private fun refreshPracticeState() {
        val f = follower ?: return
        started = f.started()
        done = f.isDone()
        currentNoteIdx = f.currentNoteIdx()
        val exp = f.expectedNote()
        expectedName = exp?.name
        expectedHz = if (exp != null && exp.midi >= 0) exp.freq.roundTo(2) else null
        expectedIsRest = exp?.isRest == true

        val frame = latestFrame
        val hz = frame.hz?.takeIf { it > 0f }?.toDouble()
        detectedHz = hz?.roundTo(2)

        if (exp != null && exp.midi >= 0 && hz != null) {
            val rawCents = 1200.0 * log2(hz / exp.freq)
            val c = rawCents - tuning.offsetCentsForMidi(exp.midi)
            detectedMidi = (69.0 + 12.0 * log2(hz / 440.0)).roundToInt()
            cents = c.roundTo(1)
            status = pitchStatus(hz, exp, tuning)
        } else {
            detectedMidi = hz?.let { (69.0 + 12.0 * log2(it / 440.0)).roundToInt() }
            cents = null
            status = null
        }

        // On the first done tick, snapshot the summary and jump to the report.
        if (f.isDone() && summary == null) {
            summary = scorer?.summary()
            screen = Screen.REPORT
        }
    }

    /** Port of `_calibration_tick_loop`. */
    private fun calibrationTick() {
        val target = calibTarget ?: return
        val frame = latestFrame
        val hz = frame.hz?.takeIf { it > 0f }
        if (hz == null) {
            calibBuffer.clear()
            calibProgress = 0f
            calibCents = null
            return
        }
        val nominal = nominalHzForString(target)
        val c = 1200.0 * log2(hz.toDouble() / nominal)
        calibCents = c.roundTo(1)
        if (kotlin.math.abs(c) > 50.0) {
            // Wrong string or wildly out of tune — restart this string.
            calibBuffer.clear()
            calibProgress = 0f
            return
        }

        calibBuffer.add(hz)
        calibProgress = (calibBuffer.size.toFloat() / CALIB_REQUIRED_TICKS).coerceAtMost(1f)
        if (calibBuffer.size < CALIB_REQUIRED_TICKS) return

        val avgHz = calibBuffer.map { it.toDouble() }.average()
        tuning.calibrateString(target, avgHz)
        tuning = Tuning(tuning.offsets) // recompose with the new offset

        calibBuffer.clear()
        calibProgress = 0f
        val next = tuning.nextUncalibrated()
        if (next == null) {
            // All four strings done: persist and move on to practice.
            calibTarget = null
            runCatching { tuningStore.save(tuning) }
            hasSavedTuning = tuning.isCalibrated()
            savedTuningAt = tuningStore.savedAt()
            rebuildScorer()
            goToPractice()
        } else {
            calibTarget = next
        }
    }

    // =======================================================================
    // Loading helpers
    // =======================================================================

    private fun loadSavedTuning() {
        val saved = tuningStore.load()
        if (saved != null && saved.isCalibrated()) {
            tuning = saved
            calibTarget = null
            hasSavedTuning = true
            savedTuningAt = tuningStore.savedAt()
        } else {
            hasSavedTuning = false
        }
    }

    private fun loadScore(name: String) {
        // Imported scores (filesDir/scores) take precedence over bundled assets.
        val bytes = scoreLibrary.read(name)
            ?: runCatching {
                getApplication<Application>().assets.open(name).use { it.readBytes() }
            }.getOrNull()
            ?: return
        val loaded = runCatching { ScoreLoader.load(bytes) }.getOrNull() ?: return
        notes = loaded.notes
        bpm = loaded.bpm
        resetEngine()
    }

    private fun resetEngine() {
        val en = engineNotes()
        follower = ScoreFollower(en, clock)
        scorer = Scorer(en, tuning)
        summary = null
        started = false
        done = false
        currentNoteIdx = -1
        countdown = 0
        cents = null
        status = null
        detectedHz = null
        detectedMidi = null
        expectedName = null
        expectedHz = null
        expectedIsRest = false
    }

    private fun rebuildScorer() {
        scorer = Scorer(engineNotes(), tuning)
    }

    // =======================================================================
    // Import (file / URL) and export (HTML report)
    // =======================================================================

    private fun refreshScores() {
        availableScores = (listAssetScores() + scoreLibrary.list()).distinct()
    }

    /**
     * Import a score from raw [bytes] (a picked file or a download). Validates by
     * parsing, persists to the library, refreshes the picker, and selects it.
     */
    fun importScore(displayName: String, bytes: ByteArray) {
        val parsed = runCatching { ScoreLoader.load(bytes) }.getOrNull()
        if (parsed == null || parsed.notes.isEmpty()) {
            importStatus = "匯入失敗：無法解析為樂譜"
            return
        }
        val stored = runCatching { scoreLibrary.save(displayName, bytes) }.getOrNull()
        if (stored == null) {
            importStatus = "匯入失敗：無法儲存"
            return
        }
        refreshScores()
        selectScore(stored)
        importStatus = "已匯入：$stored（${parsed.notes.size} 音）"
    }

    /** Download a score from [url] and import it. */
    fun importFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        importStatus = "下載中…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(trimmed).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000; readTimeout = 15_000; instanceFollowRedirects = true
                    }
                    try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }
                }
            }
            result.onSuccess { importScore(trimmed, it) }
                .onFailure { importStatus = "下載失敗：${it.message ?: "未知錯誤"}" }
        }
    }

    fun clearImportStatus() { importStatus = null }

    /**
     * Build the practice report as an HTML file in cache and return it (for the
     * UI to share via FileProvider). Null if there's no summary yet.
     */
    fun buildReportFile(): File? {
        val s = summary ?: return null
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val html = ReportHtml.build(s, selectedScore, stamp)
        val dir = File(getApplication<Application>().cacheDir, "reports").apply { mkdirs() }
        val safe = selectedScore.substringBeforeLast('.').replace(Regex("""[^\w\-]"""), "_")
        val file = File(dir, "report_$safe.html")
        return runCatching { file.writeText(html); file }.getOrNull()
    }

    private fun listAssetScores(): List<String> = runCatching {
        // Only the unambiguous MusicXML extensions. A bare ".xml" filter also
        // catches library/framework config assets merged into assets/ (e.g.
        // attributes_config.xml), which are not scores — so we exclude it.
        getApplication<Application>().assets.list("")
            ?.filter { it.endsWith(".musicxml", true) || it.endsWith(".mxl", true) }
            ?.sorted()
            ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * Fire exactly one tick of the active screen's loop. Test-only entry point
     * (used when [autoDrive] is false) — equivalent to one `delay(TICK_MS)`
     * iteration of [startTicking]. Lets Robolectric drive the engine one frame at
     * a time, fully deterministically, without depending on coroutine timing.
     */
    internal fun stepForTest() {
        when (screen) {
            Screen.TUNING -> calibrationTick()
            Screen.PRACTICE -> practiceTick()
            else -> { /* idle */ }
        }
    }

    override fun onCleared() {
        super.onCleared()
        tickJob?.cancel()
        countdownJob?.cancel()
        beatJob?.cancel()
        metronome.release()
        if (sourceStarted) pitchSource.stop()
    }

    companion object {
        /** Tick period; matches `time.sleep(0.05)` in `main.py`. */
        const val TICK_MS = 50L

        /** Consecutive stable ticks needed to accept a string (1.5 s at 50 ms). */
        const val CALIB_REQUIRED_TICKS = 30

        private const val DEFAULT_SCORE = "g_major_scale.musicxml"

        /**
         * Factory so the ViewModel can be built with an injected [PitchSource].
         * The app passes [AudioPitchSource]; tests pass a
         * [com.cellocoach.audio.FakePitchSource].
         */
        fun factory(app: Application, source: PitchSource): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    PracticeViewModel(app, source) as T
            }
    }
}

private fun log2(x: Double): Double = ln(x) / ln(2.0)

/** Round to [decimals] places (Python `round()` parity). */
private fun Double.roundTo(decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    return (this * factor).roundToInt() / factor
}
