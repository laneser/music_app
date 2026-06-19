# CLAUDE.md — CelloCoach 開發指引

大提琴練習助手（Android / Kotlin + Jetpack Compose）。從 `../music_code` 的
Flask + 瀏覽器版移植為原生 App。架構與決策見 [`docs/arc42/`](docs/arc42/)，模組
簽章見 [`CONTRACTS.md`](CONTRACTS.md)。

## 開發環境

一律在 **devcontainer** 內建置與測試（主機不需裝 Android SDK / JDK）。容器映像見
`.devcontainer/Dockerfile`（JDK 17 + Android SDK 34 + emulator + Gradle 8.7）。

主機若用 CLI（非 VS Code），等效的 docker 叫法：

```bash
docker run --rm -v "$PWD":/workspace -w /workspace \
  -v cellocoach-gradle:/root/.gradle -v cellocoach-android:/root/.android \
  -e ANDROID_SDK_ROOT=/opt/android-sdk -e GRADLE_USER_HOME=/root/.gradle \
  --user root cellocoach-dev:latest bash -lc './gradlew <task>'
```

> **務必掛 `-v cellocoach-android:/root/.android`**：debug keystore 放在這裡。
> 不掛的話每次 build 都會產生新 keystore → 簽章不同 → `adb install -r` 會因
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 失敗、得先解除安裝。

## 測試策略（重要）

分兩層，**都不需要實體手機**：

1. **單元 + Robolectric UI/E2E（JVM，免 emulator、免麥克風）— 平時主力**
   ```bash
   ./gradlew testDebugUnitTest
   ```
   - core 純演算法單元測試。
   - Compose 畫面流程（校正 / 練習 / 報告）以 `FakePitchSource` 餵腳本音高、
     `testTag` 斷言。秒級回饋，CI 直接可跑。

2. **Instrumented E2E（真機畫面驗證）→ 一律用 emulator，不要用實體手機**
   ```bash
   bash scripts/run-emulator-tests.sh    # headless KVM emulator + connectedDebugAndroidTest
   ```
   容器需以 `--device=/dev/kvm` 啟動（devcontainer 已設定）。

### 為什麼 E2E / UI 自動化用 emulator，不用實體手機

- 不用一直插著手機、不用顧線。
- emulator 可自由用 `adb shell input tap` 驅動 UI；**實體手機（尤其 Xiaomi /
  HyperOS）預設禁止 adb 模擬點擊**，要另外開「USB 偵錯（安全設定）」才行，
  否則自動點擊無聲失效——非常容易誤判成 App bug。
- emulator 乾淨、可重現、可丟進 CI。

實體手機**只**用於：真實麥克風的手動體驗，或最終 sideload 安裝確認。

> 需要用 adb 自動驅動畫面時，記得 App 已開 `testTagsAsResourceId`，可用
> `uiautomator dump` 取 `resource-id`（即各 `TestTags` 字串）來精準點擊。

## 部署到實機（WSL2）

平時開發不需要；要實機體驗時見 [arc42 §11.3](docs/arc42/11_risks_and_technical_debt.md#113-實機部署路徑wsl2)。
WSL2 最簡路徑是直接呼叫 Windows 的 `adb.exe`（免 usbipd）：

```bash
bash scripts/deploy-windows-adb.sh
```

## 慣例

- 改演算法務必對照 `../music_code/*.py` 與 `CONTRACTS.md`，保持行為一致。
- 任何被測元件都掛 `Modifier.testTag(TestTags.*)`；tag 字串集中在
  `ui/TestTags.kt`。
- core（`com.cellocoach.core`）維持純 Kotlin、無 Android 相依，才能在 JVM 測。
- 提交前至少跑過 `./gradlew testDebugUnitTest`。
