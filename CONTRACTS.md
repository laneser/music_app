# CelloCoach 實作契約（給並行開發 agent 的單一事實來源）

本檔定義所有模組的套件、類別與函式簽章。**請嚴格遵守簽章**，否則無法整合編譯。
原始 Python 參考實作在 `/home/lane/music_code/`（`score_loader.py`, `pitch_detector.py`,
`score_follower.py`, `scorer.py`, `tuning.py`, `main.py`）。本 Android app 把原本
「瀏覽器偵測音高→POST 給 Flask」的架構改成**全部在 App 內**進行，移除原 README 中
HTTPS / secure-context / LAN 連線的痛點。

## 套件結構
- `com.cellocoach.core` — 純 Kotlin 演算法（可在 JVM 單元測試，無 Android 相依）
- `com.cellocoach.audio` — AudioRecord 麥克風 + 假來源
- `com.cellocoach.data` — 校正持久化
- `com.cellocoach.ui` — Jetpack Compose 畫面 + ViewModel
- `com.cellocoach.MainActivity`

## 已存在（不要重寫）— `core/ScoreNote.kt`, `core/Pitch.kt`
```kotlin
data class ScoreNote(val start: Double, val end: Double, val midi: Int, val name: String) {
    val isRest: Boolean              // midi < 0
    val freq: Double                 // A=440；rest 為 0
    companion object { const val REST = -1 }
}
fun midiToHz(midi: Int): Double
fun midiToHz(midi: Double): Double
fun hzToMidi(hz: Double): Int       // 最近整數 MIDI
fun centsBetween(hz: Double, refHz: Double): Double

data class PitchFrame(val hz: Float?, val rms: Float)
interface PitchSource { fun start(onFrame: (PitchFrame) -> Unit); fun stop() }
fun interface Clock { fun nowNanos(): Long }
val SystemClock: Clock
```

## 要實作的核心模組

### `core/ScoreLoader.kt`
移植 `score_loader.py`。**不可用 music21**；以 JDK `javax.xml`(DocumentBuilder) 自己解析 MusicXML。
```kotlin
data class LoadedScore(val notes: List<ScoreNote>, val bpm: Double)
object ScoreLoader {
    /** bytes 可為純 XML 或 .mxl(ZIP，magic bytes "PK")，需自動解壓並取出主譜 XML。 */
    fun load(bytes: ByteArray, bpmOverride: Double? = null): LoadedScore
}
```
解析規則（只取第一個 part，單聲部）：
- ZIP 偵測：`bytes[0]=='P' && bytes[1]=='K'` → 解 zip，讀 `META-INF/container.xml` 的 rootfile full-path，否則取第一個非 META-INF 的 `.xml/.musicxml`。
- tempo：`bpmOverride` > 樂譜中第一個 `<sound tempo="..">` 或 `<metronome>`(beat-unit+per-minute) > 預設 120。
- 逐 measure 累積時間。每個 measure 內維護 `divisions`（來自 `<attributes><divisions>`）。
- `<note>`：`<duration>` / divisions = quarterLength；含 `<rest>` → midi=REST，name="rest"；含 `<pitch>`(step,octave,alter) → 轉 MIDI：`midi = (octave+1)*12 + stepSemitone[step] + alter`，stepSemitone: C0 D2 E4 F5 G7 A9 B11；name 用 `"$step$octave"`（有 alter 時加 #/b）。
- `<note>` 含 `<chord/>`：和弦音與「前一個音」同起點，取最高音（不前進時間游標）。
- `<note>` 前進時間游標；`<backup><duration>` 後退；`<forward><duration>` 前進。
- start = 目前時間游標(quarter) × (60/bpm)；end = start + quarterLength×(60/bpm)。
- 與 Python 對齊：rests 也要保留在 timeline。

### `core/PitchDetector.kt`
移植瀏覽器端 Pitchy 的 normalized autocorrelation（McLeod-style）。純運算。
```kotlin
class PitchDetector(
    val sampleRate: Int = 44100,
    val fmin: Double = 60.0,       // CELLO_FMIN
    val fmax: Double = 1100.0,     // CELLO_FMAX
    val clarityThreshold: Double = 0.9,
) {
    /** 回傳偵測到的 Hz；無清晰音高(雜訊/靜音/超出範圍)回 null。 */
    fun detect(samples: FloatArray): Float?
}
```
做法：normalized square difference function（NSDF）/ autocorrelation，找最高 peak（過 clarityThreshold），用拋物線內插求精確 lag，hz = sampleRate/lag；超出 [fmin,fmax] 回 null。RMS 太低（靜音）回 null。

### `core/ScoreFollower.kt`
**逐行移植** `score_follower.py`。常數：`ADVANCE_THRESHOLD_TICKS=5`, `LOOKAHEAD=3`, `TIMEOUT_FACTOR=2.5`。
用注入的 `Clock`（預設 `SystemClock`）取代 `time.monotonic()`（單位秒 = nanos/1e9）。
```kotlin
class ScoreFollower(private val notes: List<ScoreNote>, private val clock: Clock = SystemClock) {
    fun start()
    fun started(): Boolean
    fun isDone(): Boolean
    fun expectedNote(): ScoreNote?
    fun currentNoteIdx(): Int           // -1 if not in range
    fun elapsed(): Double               // 秒
    fun observe(detectedMidi: Int?)
}
```
邏輯完全比照 Python：hard timeout、rest 依時間、重複同音 80% 前進、lookahead 命中需連續 ADVANCE_THRESHOLD_TICKS。

### `core/Tuning.kt`
移植 `tuning.py`。
```kotlin
val STRINGS: List<Pair<String,Int>>   // C36 G43 D50 A57
val STRING_NAMES: List<String>
fun nominalHzForString(name: String): Double
fun stringForMidi(midi: Int): String
class Tuning(val offsets: MutableMap<String, Double> = mutableMapOf()) {
    fun isCalibrated(): Boolean
    fun calibrateString(name: String, detectedHz: Double): Double
    fun offsetCentsForMidi(midi: Int): Double
    fun clear()
    fun asMap(): Map<String, Double>          // 四捨五入到 0.1
    fun nextUncalibrated(): String?
}
```

### `core/Scorer.kt`（含診斷）
移植 `scorer.py` + `main.py` 的 status 判定 + README 提到的自動診斷。
```kotlin
class NoteResult(val index:Int, val expectedMidi:Int, val expectedName:String, val start:Double, val end:Double) {
    var samples:Int; var voicedSamples:Int; var correctSamples:Int
    val centsValues: MutableList<Double>
    val voicedMidiCounts: MutableMap<Int,Int>
    val isRest:Boolean; val sustain:Double; val meanCents:Double?  // trimmed mean 0.2
    val intonation:Double; val pitchCorrect:Boolean; val modalPlayedMidi:Int?; val score:Double
}
data class NoteSummary(val i:Int, val name:String, val expectedMidi:Int, val playedMidi:Int?,
    val score:Double, val pitchOk:Boolean, val cents:Double?, val sustain:Double)
data class PracticeSummary(val score:Double, val nTotal:Int, val nCorrect:Int,
    val meanCents:Double?, val duration:Double, val notes:List<NoteSummary>, val diagnostics:List<String>)
class Scorer(val notes:List<ScoreNote>, val tuning: Tuning = Tuning()) {
    fun observe(noteIdx:Int, detectedHz:Float?)
    fun summary(): PracticeSummary
}
/** 即時 status：依 cents/midi 回 "good"(<20) / "close"(<50) / "off"(>=50) / "wrong"(midi 不符) */
enum class PitchStatus { GOOD, CLOSE, OFF, WRONG }
fun pitchStatus(detectedHz: Double, expected: ScoreNote, tuning: Tuning): PitchStatus
```
診斷規則（產生繁中字串，無問題則空 list）：
- 整體偏移：|meanCents| ≥ 10 → 「整體音準偏{高/低} {x}¢」。
- 重複錯音：同一 expectedName 出現 ≥2 次且都 !pitchOk → 「{name} 反覆拉錯（{n} 次）」。
- 系統性偏移：某弦上的音平均 cents 偏移 ≥ 15 → 「{C/G/D/A} 弦的音普遍偏{高/低}」。

### `audio/AudioPitchSource.kt`（實機麥克風，不需單元測試）
```kotlin
class AudioPitchSource(private val detector: PitchDetector = PitchDetector()) : PitchSource {
    override fun start(onFrame: (PitchFrame) -> Unit)   // AudioRecord 背景 thread，~20Hz 讀取 → detect → onFrame
    override fun stop()
}
```
需處理 RECORD_AUDIO 權限由 UI 層取得；此類別假設已有權限。

### `audio/FakePitchSource.kt`（測試/預覽用）
```kotlin
/** 依腳本回放 PitchFrame，用於 Robolectric/預覽，免真實麥克風。 */
class FakePitchSource(private val frames: List<PitchFrame> = emptyList()) : PitchSource {
    fun emit(frame: PitchFrame)            // 手動推一格（測試逐步驅動）
    override fun start(onFrame: (PitchFrame) -> Unit)
    override fun stop()
}
```

### `data/TuningStore.kt`
等價於 `~/.cello-practice/tuning.json` 的持久化。用 app `filesDir/tuning.json`（JSON 純文字）。
```kotlin
class TuningStore(private val dir: java.io.File) {
    fun load(): Tuning?            // 不存在/壞檔回 null（需四弦齊全）
    fun save(tuning: Tuning)       // 僅在 isCalibrated 時寫入；含 savedAt ISO 時間
    fun savedAt(): String?
}
```

## UI 行為（com.cellocoach.ui）
- `MainActivity` setContent → `CelloCoachApp(pitchSource)`，預設用 `AudioPitchSource`；測試可注入 `FakePitchSource`。
- 畫面（單一 Activity + Compose 狀態切換，無需 Navigation 套件）：
  1. **Home**：選曲（assets 內 `g_major_scale.musicxml` 等）、顯示已存校正狀態、「開始練習」。
  2. **Tuning**：依序 C→G→D→A，顯示目前目標弦、即時 cents、進度條（30 tick）；可「略過」。完成存檔。
  3. **Practice**：原生 Compose 五線譜 `ScoreView`（畫五線譜、音符、隨演奏移動的游標、目前音 good/close/off/wrong 上色）、即時面板（應拉 vs 你拉、cents、status 顏色）、節拍器 4 拍倒數、開始前倒數。
  4. **Report**：總分、對音 X/Y、平均 cents、每顆音明細清單、診斷。
- `PracticeViewModel`：持有 ScoreFollower + Scorer + Tuning，訂閱 PitchSource，50ms tick 邏輯比照 `main.py` 的 `_practice_tick_loop` 與 `_calibration_tick_loop`。狀態以 Compose `State`/`StateFlow` 暴露。
- **測試掛勾**：所有可測元件加 `Modifier.testTag(...)`，tag 命名集中放 `ui/TestTags.kt`（例：`home_start`, `tuning_skip`, `practice_cursor`, `report_score`...）。

## 測試
- 單元測試（`app/src/test`，JVM）：core 各模組。
- Robolectric Compose UI 測試（`app/src/test`，`@RunWith(RobolectricTestRunner::class)` + `createComposeRule()`）：注入 `FakePitchSource` 餵腳本音高，驗證：校正流程、練習游標前進、回饋顏色、報告數字。**這是「mock 錄音」的 e2e 螢幕驗證**，可在 devcontainer 內免 emulator 執行。
- Instrumented（`app/src/androidTest`）：一支煙霧測試，於真機/emulator 啟動 App 驗證主畫面（透過 USB 部署）。

## 樣式/相依
- Material3、Compose BOM 已設定。主題 `Theme.CelloCoach`。
- 禁止新增未在 `app/build.gradle.kts` 宣告的相依。
