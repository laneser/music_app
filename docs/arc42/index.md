# CelloCoach 架構文件（arc42）

CelloCoach 是大提琴練習的即時音準與節奏教練，現以**原生 Android App**重新實作。本文件依 [arc42](https://arc42.org) 標準章節結構撰寫，描述系統的目標、限制、脈絡與解決策略。

> 背景：原始版本（參考實作於 `/home/lane/music_code/`）是 Flask 後端 + 瀏覽器前端的 Web 架構，麥克風偵測在瀏覽器端進行，再 `POST /pitch` 給 Flask。這個架構在 LAN／行動裝置上飽受 HTTPS／secure-context／連線設定之苦。CelloCoach Android 把**全部運算搬進 App 內**，徹底移除這些痛點。實作契約見 [`/home/lane/music_app/CONTRACTS.md`](../../CONTRACTS.md)。

## 章節索引

| 章節 | 內容 |
|---|---|
| [01 介紹與目標](01_introduction_and_goals.md) | 系統要解決的問題、品質目標、利害關係人 |
| [02 架構限制](02_architecture_constraints.md) | 技術／組織／慣例限制（Kotlin/Compose、JDK17、devcontainer、離線、on-device） |
| [03 系統脈絡與範圍](03_context_and_scope.md) | 系統邊界、外部介面，與原 Web 架構的對比 |
| [04 解決策略](04_solution_strategy.md) | 核心架構決策，README 功能對 Android 模組的對應表 |
| [05 建構區塊視圖](05_building_block_view.md) | 模組分解與套件結構 |
| [06 執行期視圖](06_runtime_view.md) | 校正／練習／報告的執行期流程 |
| [07 部署視圖](07_deployment_view.md) | devcontainer、emulator、實機 USB 部署 |
| [08 橫切概念](08_crosscutting_concepts.md) | 跨模組的共通設計（音訊、狀態、測試掛勾） |
| [09 架構決策（ADR）](09_architecture_decisions.md) | 原生 Canvas 渲染、裝置端偵測、Robolectric 測試三大決策 |
| [10 品質需求](10_quality_requirements.md) | 延遲、準確度、離線、可測試性 |
| [11 風險與技術債](11_risks_and_technical_debt.md) | 領域限制 + Android 環境風險（含 USB／adb 連接實機細節） |
| [12 詞彙表](12_glossary.md) | cents、MIDI、MusicXML、NSDF、DTW、sustain、空弦等 |

## 名詞速查

| 名詞 | 說明 |
|---|---|
| MusicXML / .mxl | 樂譜交換格式（W3C 標準）；`.mxl` 為 ZIP 壓縮版 |
| cents（音分） | 半音的 1/100，音準偏差單位 |
| score follower | 跟譜器，結合音高與時間判斷學生拉到哪一顆音 |
| NSDF | Normalized Square Difference Function，音高偵測演算法 |
| 校正（calibration） | 拉四條空弦（C G D A），以實際琴音作為評分基準 |
