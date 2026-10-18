# ddo-tts（Edge 朗读修复版）

> 基于 [wangz-code/ddo-tts](https://github.com/wangz-code/ddo-tts) 的个人修改版。
> 修复 **2026 年 10 月起 Edge 大声朗读完全没声音** 的问题，并把朗读倍速做成真正可调（0.5x ~ 5.0x）。

## 这个版本改了什么

| # | 改动 | 说明 |
|---|---|---|
| 1 | **修复 Edge 大声朗读 403** | 微软 2026-10 起强制 `Sec-MS-GEC` v2 token，旧的 v1 token 握手一律 403。这里按 [rany2/edge-tts PR #492](https://github.com/rany2/edge-tts/pull/492) 移植了 v2 生成算法（CS64 + SHA256），`Sec-MS-GEC-Version` 同步改为 `2-…` |
| 2 | **朗读倍速真的能调** | 朗读面板的拉杆现在按界面显示的 **0.5x ~ 5.0x** 生效；≤2x 交给微软合成，超过 2 倍的部分用 ExoPlayer 补速（保持音高） |
| 3 | **失败不再静默卡死** | 以前 WebSocket 403 时音频管道不关闭，表现为「点了没反应也不报错」；现在会关闭管道并打日志 |
| 4 | **固定签名 + 自动出包** | GitHub Actions 一键编译，签名密钥缓存在 CI，之后可以直接覆盖升级 |

## 下载

到本仓库的 **Releases** 下载最新的 `legado-edge-*.apk`。

> 这是 **debug 包**，包名 `io.legado.app.debug`，可以和官方版共存。

### 首次安装（重要）

如果你之前装过别的 debug 包（签名不同），Android 不允许覆盖安装：

1. 旧版 → **备份**（WebDAV 或本地文件）
2. **卸载**旧的「阅读」debug 版
3. 安装本 APK
4. 打开 → **恢复备份**

装过一次之后，后续版本都是同一把签名，**直接覆盖升级即可**。

## 使用方法

### 调速

- **朗读面板底部拉杆**：0.5x ~ 5.0x，拖到 3.0 就是 3 倍
- **朗读引擎 → Edge大声朗读 → 右侧铅笔**：嗓音下拉 + 语速滑杆（和主拉杆是同一个设置，两边同步）
- 改完速度需要**停止再重新朗读**，旧缓存音频会按新速度重新合成

### 音质提示

- ≤ 2.0x：微软直接合成，音质最好
- 2.5x ~ 3.5x：播放器补速，略有机械感
- 4.0x ~ 5.0x：明显加速感，适合赶进度

## 为什么之前会坏

**1）token 换代**

旧代码：

```
Sec-MS-GEC = SHA256(ticks + TrustedClientToken)        # 64 位 hex
Sec-MS-GEC-Version: 1-143.0.3650.75
```

微软 2026-10 起要求：

```
Sec-MS-GEC = "<16 位 CS64>-<64 位 SHA256>"
Sec-MS-GEC-Version: 2-…
```

时间线：10/10 前后各地开始 403 → 10/13 上游 [edge-tts](https://github.com/rany2/edge-tts) 用 PR #492 修复。所有基于旧版 edge-tts 的客户端同时失效，不只是这个 App。

**2）SSML 语速有硬上限**

实测同一段文字（24kHz/48kbps CBR，用音频字节数反推时长）：

| `rate` | 时长 |
|---|---|
| `+0%` | 8.71 s |
| `+50%` | 5.83 s |
| `+100%` | **4.39 s** |
| `+150%` | 4.39 s |
| `+200%` | 4.39 s |

`+100%` 以上服务端直接忽略，所以「调到 5 倍跟 2 倍一样」是微软的限制，不是 App 的问题。这也是为什么本版本把 >2x 的部分改成播放器补速。

## 自己编译

### GitHub Actions（推荐，零本地安装）

1. Fork 本仓库
2. **Actions** → **Build APK** → **Run workflow**
3. 等 10~20 分钟，在该次运行页面底部下载 artifact

### 本地编译

```bash
git clone https://github.com/<your-name>/ddo-tts.git
cd ddo-tts
./gradlew assembleAppDebug
# 产物：app/build/outputs/apk/app/debug/*.apk
```

需要 **JDK 17** 和 **Android SDK**（platform 36 / build-tools 36）。

## 目录说明

| 文件 | 作用 |
|---|---|
| `app/src/main/java/io/legado/app/service/SecMsGec.kt` | Sec-MS-GEC v2 token 生成（CS64 + SHA256） |
| `app/src/main/java/io/legado/app/service/EdgeSpeed.kt` | 倍速换算：SSML ≤2x + 播放器补速 |
| `app/src/main/java/io/legado/app/service/EdgeSpeakFetch.kt` | Edge 朗读 WebSocket 客户端 |
| `app/src/main/java/io/legado/app/service/TTSEdgeAloudService.kt` | 朗读服务（缓存、播放、进度） |
| `app/src/main/java/io/legado/app/ui/book/read/config/SpeakEngineDialog.kt` | 引擎/嗓音/语速设置界面 |
| `app/src/test/java/io/legado/app/SecMsGecTest.kt` | token 向量测试（CI 会先跑） |
| `app/src/test/java/io/legado/app/EdgeSpeedTest.kt` | 倍速换算测试 |

## 致谢

- 上游 App：[wangz-code/ddo-tts](https://github.com/wangz-code/ddo-tts)
- token 算法：[rany2/edge-tts](https://github.com/rany2/edge-tts)（PR #492，抓包验证的 v2 实现）
