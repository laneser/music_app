# CelloCoach（Android）

> 在家練琴的即時音準與評分 App · A real-time pitch & rhythm coach for cello,
> rebuilt as a native Android app.

CelloCoach 讀進 MusicXML 樂譜，用手機麥克風即時聽你拉琴，跟著譜面比對音準與
節奏，結束後給一份詳細的練習報告（總分、對音 X/Y、平均 cents、每顆音明細、
自動診斷）。

這是參考實作（瀏覽器偵測音高 → Flask server，見
[`/home/lane/music_code`](../music_code/README.md)）的 Android 重寫版。核心改變
是把整條音訊管線搬進 App 內，**移除原版的 HTTPS / secure-context / LAN 連線
痛點**。

## 功能總覽

- **原生五線譜** — Jetpack Compose Canvas 渲染 MusicXML，光標隨演奏移動、依
  good / close / off / wrong 上色（無 WebView）。
- **裝置端即時音準** — `AudioRecord` + 純 Kotlin NSDF 音高偵測，全程離線。
- **四弦校正** — 開練前拉 C→G→D→A 空弦，以你這把琴的實際音高為評分基準，
  結果存於 `filesDir/tuning.json`。
- **練習報告與自動診斷** — 整體偏移、重複錯音、單弦系統性偏移。
- **節拍器倒數** — 依樂譜 BPM 的 4 拍預備。

## 架構文件

設計細節與決策請見 arc42 文件：

- [9. 架構決策（ADR）](docs/arc42/09_architecture_decisions.md) — 原生 Canvas
  渲染、裝置端偵測、Robolectric 測試三大決策。
- [10. 品質需求](docs/arc42/10_quality_requirements.md) — 延遲、準確度、離線、
  可測試性。
- [11. 風險與技術債](docs/arc42/11_risks_and_technical_debt.md)
- [12. 詞彙表](docs/arc42/12_glossary.md)
- 實作簽章（單一事實來源）：[CONTRACTS.md](CONTRACTS.md)

## 環境需求

- 不必在主機裝 Android SDK — 一切都在 devcontainer 內。
- 主機需求：Docker、（跑 emulator 時）支援 [KVM](docs/arc42/12_glossary.md#kvm)、
  （部署實機時）能把 USB 轉進容器（WSL2 用 `usbipd-win`）。

## 在 devcontainer 開啟

1. 用 VS Code 開啟本資料夾，選 **Reopen in Container**（或用
   `devcontainer` CLI）。容器映像見
   [`.devcontainer/Dockerfile`](.devcontainer/Dockerfile)：JDK 17 + Android SDK
   34 + build-tools + emulator + Gradle 8.7。
2. 首次建立會自動跑 [`scripts/postcreate.sh`](scripts/postcreate.sh)：產生
   Gradle wrapper、寫入 `local.properties`、暖機相依。
3. 容器以 `--device=/dev/kvm`、`--device=/dev/bus/usb`、`--privileged` 啟動
   （見 [`.devcontainer/devcontainer.json`](.devcontainer/devcontainer.json)），
   分別供 emulator 加速與實機 USB 部署使用。

## 跑測試

測試策略見
[ADR-003](docs/arc42/09_architecture_decisions.md#adr-003-robolectric-jvm-可執行的-uie2e-測試)。

### 單元測試 + Robolectric UI/E2E（JVM，免 emulator）

在 devcontainer 內：

```bash
./gradlew testDebugUnitTest
```

涵蓋：

- **JVM 單元測試** — `com.cellocoach.core` 各純演算法模組（ScoreLoader /
  PitchDetector / ScoreFollower / Tuning / Scorer）。
- **Robolectric Compose UI/E2E** — 注入 `FakePitchSource` 餵腳本音高
  （mock 麥克風），以 `testTag` 驗證校正流程、練習光標前進、回饋顏色、報告
  數字。**無需 emulator 或實體麥克風**，CI/devcontainer 直接可跑。

### 選用：instrumented 煙霧測試（真機 / emulator）

需要 [KVM](docs/arc42/12_glossary.md#kvm) 或 USB 連線的實機：

```bash
# headless KVM emulator + connectedDebugAndroidTest
bash scripts/run-emulator-tests.sh
```

見 [`scripts/run-emulator-tests.sh`](scripts/run-emulator-tests.sh)。

## 透過 USB 部署到實機

部署路徑與取捨見 [arc42 §11.3](docs/arc42/11_risks_and_technical_debt.md#113-實機部署路徑wsl2)。

### WSL2（建議）：直接用 Windows adb，免 usbipd

手機插在 Windows、Windows 已有 `adb.exe`（`winget install Google.PlatformTools`），
WSL2 可直接呼叫它，**不需要把 USB 送進 WSL**：

```bash
bash scripts/deploy-windows-adb.sh   # build → 複製到 Windows 路徑 → adb.exe install → 啟動
```

手機需開「USB 偵錯」並在彈窗點「允許」（`adb.exe devices` 顯示 `unauthorized` 時，
腳本會等你授權）。詳見 [`scripts/deploy-windows-adb.sh`](scripts/deploy-windows-adb.sh)。

### 容器內 Linux adb（usbipd-win 把 USB 送進 WSL）

```bash
bash scripts/deploy-usb.sh
```

此腳本（[`scripts/deploy-usb.sh`](scripts/deploy-usb.sh)）會
`assembleDebug` → `adb install -r` → 啟動 App，需先把 USB 轉進 WSL。

WSL2 + Windows 前置步驟：

1. 在 Windows：`usbipd list` → `usbipd bind --busid <id>` →
   `usbipd attach --wsl --busid <id>`。
2. 容器須以 `--device=/dev/bus/usb --privileged` 啟動（devcontainer 已設定），
   或在主機跑 `adb -a nodaemon server` 並依賴 `ADB_SERVER_SOCKET`。
3. 手機開啟 USB 偵錯並接受 RSA 提示。

USB passthrough 的脆弱性與替代路徑見
[11 章風險](docs/arc42/11_risks_and_technical_debt.md#51-風險)。

## 專案結構

```
.
├── app/
│   └── src/
│       ├── main/java/com/cellocoach/   # core / audio / data / ui / MainActivity
│       ├── main/assets/                # g_major_scale.musicxml, twinkle.mxl
│       ├── test/                       # JVM 單元 + Robolectric UI/E2E
│       └── androidTest/                # instrumented 煙霧測試
├── docs/arc42/                         # 架構文件
├── scripts/                            # postcreate / run-emulator-tests / deploy-usb
├── .devcontainer/                      # Dockerfile + devcontainer.json
└── CONTRACTS.md                        # 模組簽章單一事實來源
```

套件邊界（見 [CONTRACTS.md](CONTRACTS.md)）：

- `com.cellocoach.core` — 純 Kotlin 演算法（JVM 可測，無 Android 相依）
- `com.cellocoach.audio` — `AudioRecord` 麥克風 + `FakePitchSource`
- `com.cellocoach.data` — 校正持久化
- `com.cellocoach.ui` — Compose 畫面 + ViewModel

## License

MIT
