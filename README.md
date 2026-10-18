# ddo-tts（Edge 朗讀修復版）

**繁體中文** ｜ [English](README.en.md)

> 基於 [wangz-code/ddo-tts](https://github.com/wangz-code/ddo-tts) 的個人修改版。
> 修復 **2026 年 10 月起 Edge 大聲朗讀完全沒聲音** 的問題，並把朗讀倍速做成真正可調（0.5x ~ 5.0x）。

## 這個版本改了什麼

| # | 改動 | 說明 |
|---|---|---|
| 1 | **修復 Edge 大聲朗讀 403** | 微軟 2026-10 起強制 `Sec-MS-GEC` v2 token，舊的 v1 token 握手一律 403。這裡依照 [rany2/edge-tts PR #492](https://github.com/rany2/edge-tts/pull/492) 移植了 v2 演算法（CS64 + SHA256），`Sec-MS-GEC-Version` 同步改為 `2-…` |
| 2 | **朗讀倍速真的能調** | 朗讀面板的拉桿現在依照介面顯示的 **0.5x ~ 5.0x** 生效；≤2x 交給微軟合成，超過 2 倍的部分改用 ExoPlayer 補速（保持音高） |
| 3 | **失敗不再靜默卡死** | 以前 WebSocket 403 時音訊管道不會關閉，表現成「點了沒反應也不報錯」；現在會關閉管道並寫入 log |
| 4 | **固定簽名 + 自動出包** | GitHub Actions 一鍵編譯，簽名金鑰快取在 CI，之後可以直接覆蓋升級 |

## 下載

到本倉庫的 **Releases** 下載最新的 `legado-edge-*.apk`。

> 這是 **debug 包**，套件名為 `io.legado.app.debug`，可以和官方版共存。

### 首次安裝（重要）

如果你之前裝過別的 debug 包（簽名不同），Android 不允許覆蓋安裝：

1. 舊版 → **備份**（WebDAV 或本機檔案）
2. **解除安裝**舊的「閱讀」debug 版
3. 安裝本 APK
4. 開啟 → **還原備份**

裝過一次之後，後續版本都是同一把簽名，**直接覆蓋升級即可**。

## 使用方法

### 調速

- **朗讀面板底部拉桿**：0.5x ~ 5.0x，拉到 3.0 就是 3 倍
- **朗讀引擎 → Edge大聲朗讀 → 右側鉛筆**：嗓音下拉 + 語速滑桿（和主拉桿是同一個設定，兩邊同步）
- 改完速度需要**停止再重新朗讀**，舊的快取音訊會依新速度重新合成

### 音質提示

- ≤ 2.0x：微軟直接合成，音質最好
- 2.5x ~ 3.5x：播放器補速，略有機械感
- 4.0x ~ 5.0x：明顯加速感，適合趕進度

## 為什麼之前會壞

**1）token 換代**

舊程式碼：

```
Sec-MS-GEC = SHA256(ticks + TrustedClientToken)        # 64 位 hex
Sec-MS-GEC-Version: 1-143.0.3650.75
```

微軟 2026-10 起要求：

```
Sec-MS-GEC = "<16 位 CS64>-<64 位 SHA256>"
Sec-MS-GEC-Version: 2-…
```

時間線：10/10 前後各地開始 403 → 10/13 上游 [edge-tts](https://github.com/rany2/edge-tts) 以 PR #492 修復。所有基於舊版 edge-tts 的客戶端同時失效，不只是這個 App。

**2）SSML 語速有硬上限**

實測同一段文字（24kHz/48kbps CBR，用音訊位元組數反推時長）：

| `rate` | 時長 |
|---|---|
| `+0%` | 8.71 s |
| `+50%` | 5.83 s |
| `+100%` | **4.39 s** |
| `+150%` | 4.39 s |
| `+200%` | 4.39 s |

`+100%` 以上伺服器直接忽略，所以「調到 5 倍跟 2 倍一樣」是微軟的限制，不是 App 的問題。這也是為什麼本版本把 >2x 的部分改成播放器補速。

## 自己編譯

### GitHub Actions（推薦，零本機安裝）

1. Fork 本倉庫
2. **Actions** → **Build APK** → **Run workflow**
3. 等 10~20 分鐘，在該次執行頁面底部下載 artifact

### 本機編譯

```bash
git clone https://github.com/<your-name>/ddo-tts.git
cd ddo-tts
./gradlew assembleAppDebug
# 產物：app/build/outputs/apk/app/debug/*.apk
```

需要 **JDK 17** 和 **Android SDK**（platform 36 / build-tools 36）。

## 目錄說明

| 檔案 | 作用 |
|---|---|
| `app/src/main/java/io/legado/app/service/SecMsGec.kt` | Sec-MS-GEC v2 token 產生（CS64 + SHA256） |
| `app/src/main/java/io/legado/app/service/EdgeSpeed.kt` | 倍速換算：SSML ≤2x + 播放器補速 |
| `app/src/main/java/io/legado/app/service/EdgeSpeakFetch.kt` | Edge 朗讀 WebSocket 客戶端 |
| `app/src/main/java/io/legado/app/service/TTSEdgeAloudService.kt` | 朗讀服務（快取、播放、進度） |
| `app/src/main/java/io/legado/app/ui/book/read/config/SpeakEngineDialog.kt` | 引擎／嗓音／語速設定介面 |
| `app/src/test/java/io/legado/app/SecMsGecTest.kt` | token 向量測試（CI 會先跑） |
| `app/src/test/java/io/legado/app/EdgeSpeedTest.kt` | 倍速換算測試 |

## 致謝

- 上游 App：[wangz-code/ddo-tts](https://github.com/wangz-code/ddo-tts)
- token 演算法：[rany2/edge-tts](https://github.com/rany2/edge-tts)（PR #492，抓包驗證的 v2 實作）
