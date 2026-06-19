# 9. 架構決策（Architecture Decisions）

本章以 ADR（Architecture Decision Record）形式記錄 CelloCoach Android app
的關鍵架構決策。每筆 ADR 說明背景、決策、考量過的替代方案，以及取捨。

相關章節：

- 品質需求與決策的對應 → [10_quality_requirements.md](10_quality_requirements.md)
- 各決策帶來的風險與技術債 → [11_risks_and_technical_debt.md](11_risks_and_technical_debt.md)
- 名詞解釋（cents、NSDF、DTW、MusicXML…） → [12_glossary.md](12_glossary.md)
- 實作簽章與模組邊界 → [../../CONTRACTS.md](../../CONTRACTS.md)

> 背景：原始參考實作（`/home/lane/music_code/`）是「瀏覽器偵測音高 → POST 給
> Flask server」的網頁架構。Android 版的核心目標是把整條管線搬進 App 內，移除
> 原 README 花大量篇幅描述的 HTTPS / secure-context / LAN 連線痛點。下列 ADR
> 記錄這次重新設計的三個主要決策。

ADR 索引：

| 編號 | 標題 | 狀態 |
|---|---|---|
| [ADR-001](#adr-001-原生-compose-canvas-樂譜渲染而非-webview--osmd) | 原生 Compose Canvas 樂譜渲染（而非 WebView + OSMD） | 已採用 |
| [ADR-002](#adr-002-裝置端音高偵測而非瀏覽器--flask-server) | 裝置端音高偵測（而非瀏覽器 + Flask server） | 已採用 |
| [ADR-003](#adr-003-robolectric-jvm-可執行的-uie2e-測試) | Robolectric JVM 可執行的 UI/E2E 測試 | 已採用 |

---

## ADR-001：原生 Compose Canvas 樂譜渲染（而非 WebView + OSMD）

**狀態：** 已採用

### 背景

參考實作用 [OpenSheetMusicDisplay (OSMD)](12_glossary.md#osmd) 在瀏覽器渲染
MusicXML 五線譜，並用 JS 移動光標。要在 Android 沿用這條路，唯一選項是塞一個
`WebView`，把 OSMD（透過 CDN 或打包的 JS bundle）與一層 JS bridge 一起載入。

App 需要呈現的內容其實很受限：單聲部、第一個 part、五線譜 + 音符 + 一個隨演奏
移動的光標 + 依即時 status 上色（good / close / off / wrong）。並不需要 OSMD
的完整排版引擎。

### 決策

用 **Jetpack Compose `Canvas`（`androidx.compose.foundation.Canvas`）原生繪製**
五線譜，實作 `ui/ScoreView.kt`。資料來源是 `core/ScoreLoader.kt` 解析出的
`List<ScoreNote>` timeline。光標位置與每顆音的上色由 `PracticeViewModel`
暴露的 Compose state 驅動。

### 替代方案

1. **WebView + OSMD（被否決）** — 與參考實作一致，排版品質最好。
2. **原生 Compose Canvas（採用）** — 自行繪製受限的五線譜子集。
3. **預先把樂譜算成點陣圖** — 失去動態光標與即時上色，否決。

### 理由與取捨

採用原生 Canvas 的理由：

- **可測試性**：渲染輸入是純 Kotlin 資料（`ScoreNote`），畫面狀態是 Compose
  state，可在 [Robolectric](12_glossary.md#robolectric)（見
  [ADR-003](#adr-003-robolectric-jvm-可執行的-uie2e-測試)）下用 `testTag`
  斷言光標位置與上色，無需在 WebView 裡跑 JS、也無須 DOM 探針。
- **離線**：不依賴 CDN、不需打包並維護一份 OSMD JS bundle，App 完全離線可用。
  對應 [品質需求：離線](10_quality_requirements.md#43-離線-offline)。
- **無 WebView**：去掉 WebView 等於去掉一整類問題 — JS bridge 的執行緒/序列化
  成本、WebView 版本破碎、context 生命週期、以及把 native 偵測結果丟進 JS 再
  回拋的延遲。對應 [品質需求：延遲](10_quality_requirements.md#41-延遲-latency)。
- **單一渲染心智模型**：整個 UI 都是 Compose，沒有「原生 / WebView」兩套世界。

接受的取捨（記錄於
[11 章](11_risks_and_technical_debt.md#52-技術債)）：

- 我們重新實作了一個**受限的**樂譜排版器。複雜記譜（連音線、多聲部、裝飾音、
  反覆記號）不像 OSMD 那樣完整支援；目前範圍是單聲部、第一個 part。
- 排版美觀度低於 OSMD。對「練習導向」的 MVP 可接受。

---

## ADR-002：裝置端音高偵測（而非瀏覽器 + Flask server）

**狀態：** 已採用

### 背景

參考實作把音訊擷取與[音高偵測](12_glossary.md#音高偵測-pitch-detection)放在
**瀏覽器**（Web Audio API + [Pitchy](12_glossary.md#pitchy)），把 `{hz, rms}`
每 ~50 ms POST 給 Flask server，server 端只是個執行緒安全的最新值持有者
（`pitch_detector.py`）。

這個設計逼出原 README 花好幾段解釋的痛點：瀏覽器只在
[secure context](12_glossary.md#secure-context-安全來源) 才允許存取麥克風。
`http://localhost` 算安全，但 `http://192.168.x.y` 與雲端 IP 不算。於是使用者
被迫處理 self-signed HTTPS 憑證警告、`chrome://flags` 白名單，或在 reverse
proxy 後面終結 TLS — 全都只是為了讓平板的麥克風能開起來。

### 決策

在 **Android App 內**完成整條音訊管線，完全不需 server：

- `audio/AudioPitchSource.kt`：用 `AudioRecord` 在背景執行緒 ~20 Hz 讀取麥克風。
- `core/PitchDetector.kt`：純 Kotlin 移植 Pitchy 的 normalized autocorrelation
  / [NSDF](12_glossary.md#nsdf)（McLeod-style），含拋物線內插與 clarity 門檻。
- 兩者皆透過 `PitchSource` 介面（見 [CONTRACTS.md](../../CONTRACTS.md)），
  讓測試可注入 `audio/FakePitchSource.kt`。

### 替代方案

1. **保留瀏覽器 + Flask（被否決）** — 重用最多既有程式碼，但把 HTTPS /
   secure-context / LAN 問題一起帶進 Android。
2. **App 內偵測（採用）** — `AudioRecord` + 純 Kotlin NSDF。
3. **App 內擷取，但偵測丟雲端** — 增加網路延遲與隱私顧慮，且無法離線，否決。

### 理由與取捨

- **消滅 secure-context / HTTPS / LAN 整類問題**：App 直接用系統的
  `RECORD_AUDIO` 執行期權限拿麥克風。沒有瀏覽器來源規則、沒有憑證、沒有
  `chrome://flags`、沒有 reverse proxy。這是本決策最大的動機。
- **延遲**：音訊樣本不再跨網路往返。擷取 → 偵測 → follower/scorer 全在同一個
  process。對應 [品質需求：延遲](10_quality_requirements.md#41-延遲-latency)。
- **離線**：完全不需網路。對應
  [品質需求：離線](10_quality_requirements.md#43-離線-offline)。
- **可測試性**：偵測邏輯是純函式 `PitchDetector.detect(FloatArray): Float?`，
  可在 JVM 單元測試直接餵合成波形驗證。`PitchSource` 介面讓 UI/E2E 測試用
  `FakePitchSource` 餵腳本音高（見
  [ADR-003](#adr-003-robolectric-jvm-可執行的-uie2e-測試)）。
- **準確度可控**：偵測與 [tuning](12_glossary.md#tuning-校正) 校正在同一程式碼庫，
  能一致地把每弦的 cents 偏移套用到評分。對應
  [品質需求：準確度](10_quality_requirements.md#42-準確度-accuracy)。

接受的取捨（記錄於
[11 章](11_risks_and_technical_debt.md)）：

- 麥克風品質與環境噪音現在由手機決定；吵雜房間的偵測穩定度是已知風險
  （[11 章](11_risks_and_technical_debt.md#51-風險)）。
- emulator 的虛擬麥克風無法真正收音，所以即時音準只能在實機驗證；
  CI 用 `FakePitchSource` 規避（[ADR-003](#adr-003-robolectric-jvm-可執行的-uie2e-測試)）。

---

## ADR-003：Robolectric JVM 可執行的 UI/E2E 測試

**狀態：** 已採用

### 背景

CelloCoach 的價值高度集中在「即時互動」：光標前進、回饋上色、校正流程、報告
數字。這些是畫面層行為，傳統上要用 instrumented 測試在 emulator/實機跑。但
emulator 在 [WSL2](12_glossary.md#wsl2) + devcontainer 環境下啟動慢、需要
[KVM](12_glossary.md#kvm)，且其虛擬麥克風無法提供真實音高 — 正是我們想在 CI 跑
端到端螢幕驗證時最不想依賴的東西。

### 決策

採**雙層測試策略**：

1. **Robolectric Compose UI/E2E 測試**（`app/src/test`，JVM 可執行）：
   用 `@RunWith(RobolectricTestRunner::class)` + `createComposeRule()`，
   注入 `FakePitchSource` 餵**腳本化的 `PitchFrame`**（mock 錄音），透過
   `Modifier.testTag(...)`（集中於 `ui/TestTags.kt`）斷言：校正流程推進、
   練習光標前進、回饋顏色、報告數字。這是「mock 麥克風」的端到端螢幕驗證，
   **在 devcontainer 內免 emulator 即可跑**，CI 友善。
2. **JVM 單元測試**（`app/src/test`）：`com.cellocoach.core` 各純演算法模組
   （ScoreLoader / PitchDetector / ScoreFollower / Tuning / Scorer）。
3. **選用的 instrumented 煙霧測試**（`app/src/androidTest`）：一支在真機 /
   emulator 啟動 App、驗證主畫面的測試，透過 USB 部署（見
   [README 的部署章節](../../README.md)）。

### 替代方案

1. **只有 instrumented 測試（被否決）** — 每次 CI 都要起 emulator，慢且脆弱，
   且無法驗證即時音準（虛擬麥克風）。
2. **Robolectric + FakePitchSource 為主，instrumented 為選用煙霧測試（採用）**。
3. **只有單元測試，不測畫面** — 漏掉最核心的互動行為，否決。

### 理由與取捨

- **可測試性 / CI**：把 `PitchSource`（[CONTRACTS.md](../../CONTRACTS.md)）做成
  可注入的介面後，整個練習/校正流程能在 JVM 上以決定性的腳本音高重放，
  毫秒級執行、零 emulator 依賴。對應
  [品質需求：可測試性](10_quality_requirements.md#44-可測試性-testability)。
- **決定性**：`FakePitchSource.emit()` 讓測試逐格驅動狀態機，避免真實音訊的
  非決定性。follower 用注入的 `Clock`（見 [CONTRACTS.md](../../CONTRACTS.md)）
  取代 wall-clock，使時間相關邏輯也可被測試控制。
- **仍保留真機驗證**：instrumented 煙霧測試守住「App 真的能在裝置上啟動」這條
  底線，補上 Robolectric 不模擬的硬體/系統行為。

接受的取捨：

- Robolectric 對 Compose 的繪製是近似的（shadow 實作），像素級渲染正確性仍需
  靠人工或 instrumented 測試確認。
- USB / emulator 路徑本身有環境脆弱性（WSL2 USB passthrough），記錄於
  [11 章](11_risks_and_technical_debt.md#51-風險)。

---

下一章：[10. 品質需求](10_quality_requirements.md)
