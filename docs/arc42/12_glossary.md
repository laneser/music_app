# 12. 詞彙表（Glossary）

本章定義 CelloCoach 文件中使用的領域與技術名詞。其他章節以連結指回此處。

相關章節：
[09 架構決策](09_architecture_decisions.md) ｜
[10 品質需求](10_quality_requirements.md) ｜
[11 風險與技術債](11_risks_and_technical_debt.md) ｜
[CONTRACTS.md](../../CONTRACTS.md)

## 領域名詞（音樂 / 音準）

### cents

音高間距的對數單位。一個半音 = 100 cents，一個八度 = 1200 cents。
公式：`cents = 1200 × log2(f / f_ref)`（見 `core/Pitch.kt` 的 `centsBetween`）。
CelloCoach 用 cents 表示「你拉的音」與「應拉的音」的偏差，並驅動 status 門檻
（good <20¢、close <50¢、off ≥50¢）。

### intonation（音準）

演奏者把音拉到正確音高的準確程度。CelloCoach 以每顆音的 cents 偏差衡量
intonation，並在報告中彙總（平均 cents、系統性偏移診斷）。見
[10.3 準確度](10_quality_requirements.md#42-準確度-accuracy)。

### MIDI

音高的整數編號標準。`midi = (octave+1)×12 + step半音 + alter`，A4=69、C2=36。
CelloCoach 內部以 MIDI 整數比對「應拉/你拉」是否為同一顆音（不同 = WRONG）。
見 `core/ScoreNote.kt`、`midiToHz` / `hzToMidi`（`core/Pitch.kt`）。

### MusicXML

樂譜交換的業界標準（W3C 規格），副檔名 `.xml` / `.musicxml`，壓縮版為
[.mxl](#mxl)。CelloCoach 用 JDK `javax.xml`（DocumentBuilder）自行解析，**不用
music21**，只取第一個 part（單聲部）。見 `core/ScoreLoader.kt`、
[CONTRACTS.md](../../CONTRACTS.md)。

### .mxl

壓縮版 MusicXML，本質是 ZIP（magic bytes `PK`）。`ScoreLoader` 以 magic bytes
偵測並解壓，讀 `META-INF/container.xml` 的 rootfile 取出主譜 XML — 不被被竄改的
副檔名騙到。

### sustain（延音 / 持音）

一顆音被穩定發聲、維持的程度。CelloCoach 的 `NoteResult.sustain` 反映該音被
voiced（有清晰音高）的取樣比例，是評分與報告的一個面向（音拉得夠不夠久/穩）。

### open string（空弦）

不按弦、直接拉空弦發出的音。大提琴四條空弦為 C2 / G2 / D3 / A3
（MIDI 36 / 43 / 50 / 57）。CelloCoach 的[校正](#tuning-校正)讓使用者依序拉
C→G→D→A 四條空弦，建立每弦的 cents 基準。見 `core/Tuning.kt`。

### tuning（校正）

把評分基準從硬綁 A=440 改成「使用者這把琴的實際空弦音高」的流程。
開練前拉四條空弦，記錄每弦相對 nominal 值的 cents 偏移，後續評分時依音所在的
弦扣掉該偏移。校正結果存於 `filesDir/tuning.json`（含 `savedAt`）。見
`core/Tuning.kt`、`data/TuningStore.kt`，及
[10.3 準確度](10_quality_requirements.md#42-準確度-accuracy)。

### score follower（跟譜器）

把「演奏者目前拉到樂譜的哪一顆音」即時定位的元件。CelloCoach 的 follower 結合
偵測音高與經過時間：命中當前音則停留，命中前方 1..LOOKAHEAD 顆並持續
ADVANCE_THRESHOLD_TICKS 才前進，超過 TIMEOUT_FACTOR×音長則強制前進。
**只能往前**（forward-only），見
[11 章風險](11_risks_and_technical_debt.md#51-風險)、`core/ScoreFollower.kt`。

## 技術名詞（演算法 / 工具 / 環境）

### 音高偵測（pitch detection）

從音訊波形估出基頻（Hz）的過程。CelloCoach 在裝置端以純 Kotlin 實作
（[NSDF](#nsdf) / autocorrelation），移植自瀏覽器的 [Pitchy](#pitchy)。見
[ADR-002](09_architecture_decisions.md#adr-002-裝置端音高偵測而非瀏覽器--flask-server)、
`core/PitchDetector.kt`。

### NSDF

Normalized Square Difference Function，McLeod Pitch Method（MPM）使用的
正規化自相關函式。CelloCoach 取最高 peak（過 clarity 門檻 0.9），以拋物線內插
求精確 lag，再 `hz = sampleRate / lag`。超出 [fmin,fmax] 或 RMS 過低回 null。
見 `core/PitchDetector.kt`。

### DTW

Dynamic Time Warping，動態時間規整 — 一種對齊兩條時序（如演奏與樂譜）的演算法，
容忍拉快/拉慢的速度差異。CelloCoach 的跟隨採「DTW 風格」的音高+時間混合策略
（forward-only 的近似，非完整 DTW），讓學生拉慢、暫停、跳音仍跟得上。見
[score follower](#score-follower-跟譜器)。

### Pitchy

瀏覽器端的 JS 音高偵測函式庫（normalized autocorrelation），參考實作所用。
Android 版以純 Kotlin 重新實作其演算法，移除瀏覽器依賴。見
[ADR-002](09_architecture_decisions.md#adr-002-裝置端音高偵測而非瀏覽器--flask-server)。

### OSMD

[OpenSheetMusicDisplay](https://opensheetmusicdisplay.org) — 在瀏覽器渲染
MusicXML 五線譜的 JS 函式庫，參考實作所用。Android 版改以原生 Compose Canvas
自繪，去掉 WebView。見
[ADR-001](09_architecture_decisions.md#adr-001-原生-compose-canvas-樂譜渲染而非-webview--osmd)。

### Robolectric

讓 Android 框架類別能在 JVM（無 emulator/裝置）下執行的測試框架。CelloCoach
用它跑 Compose UI/E2E 測試（注入 `FakePitchSource`），在 devcontainer/CI 免
emulator 驗證螢幕行為。見
[ADR-003](09_architecture_decisions.md#adr-003-robolectric-jvm-可執行的-uie2e-測試)。

### WSL2

Windows Subsystem for Linux 2 — 在 Windows 上跑 Linux 核心的環境，本專案
devcontainer 的常見宿主。實機 USB 需透過 `usbipd-win` 轉發進 WSL2，連線較脆弱。
見 [11 章風險](11_risks_and_technical_debt.md#51-風險)、
[scripts/deploy-usb.sh](../../scripts/deploy-usb.sh)。

### KVM

Kernel-based Virtual Machine — Linux 的硬體虛擬化，Android emulator 加速所需。
devcontainer 以 `--device=/dev/kvm` 啟動才能跑 headless emulator。見
[run-emulator-tests.sh](../../scripts/run-emulator-tests.sh)。

### secure context（安全來源）

瀏覽器只在「安全來源」（HTTPS 或 `http://localhost`）才允許存取麥克風等敏感
API。`http://192.168.x.y` 與雲端 IP **不算**安全來源 — 這正是參考實作被迫處理
self-signed HTTPS / `chrome://flags` / reverse proxy 的根因。Android 版用系統
`RECORD_AUDIO` 權限直接取得麥克風，**徹底消除**此類問題。見
[ADR-002](09_architecture_decisions.md#adr-002-裝置端音高偵測而非瀏覽器--flask-server)。

---

上一章：[11. 風險與技術債](11_risks_and_technical_debt.md) ｜
回到 [9. 架構決策](09_architecture_decisions.md)
