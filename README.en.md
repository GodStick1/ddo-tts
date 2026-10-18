# ddo-tts (Edge Read-Aloud fix)

[繁體中文](README.md) ｜ **English**

> A personal fork of [wangz-code/ddo-tts](https://github.com/wangz-code/ddo-tts).
> It fixes **Edge read-aloud going silent since October 2026**, and makes the speaking rate actually adjustable (0.5x – 5.0x).

## What changed

| # | Change | Details |
|---|---|---|
| 1 | **Fixed Edge read-aloud 403** | Microsoft made `Sec-MS-GEC` **v2** tokens mandatory in Oct 2026; v1 tokens now get HTTP 403 on the WebSocket handshake. Ported the v2 generator (CS64 + SHA256) from [rany2/edge-tts PR #492](https://github.com/rany2/edge-tts/pull/492) and bumped `Sec-MS-GEC-Version` to `2-…` |
| 2 | **Real speed control** | The read-aloud slider now matches the label (**0.5x – 5.0x**). Anything up to 2x is synthesized by Microsoft; the rest is handled by ExoPlayer with pitch preserved |
| 3 | **No more silent hang** | Previously a 403 never closed the audio pipe, so playback just froze with no error. Now the pipe is closed and the failure is logged |
| 4 | **Stable signing + CI builds** | GitHub Actions builds the APK; the signing key is cached in CI, so later builds install over previous ones |

## Download

Grab the latest `legado-edge-*.apk` from **Releases**.

> This is a **debug build** (`io.legado.app.debug`) and can coexist with the official app.

### First install (important)

Android refuses to overwrite an APK signed with a different key. If you installed an earlier debug build:

1. Back up your data in the old app (WebDAV or local file)
2. **Uninstall** the old debug build
3. Install this APK
4. Open the app → **restore backup**

After that, later versions share the same key and install as normal updates.

## Usage

### Speed

- **Slider at the bottom of the read-aloud panel**: 0.5x – 5.0x
- **Speak engine → Edge read-aloud → pencil icon**: voice picker + speed slider (same setting as the main slider, kept in sync)
- Stop and restart reading after changing speed; cached audio is re-synthesized

### Audio quality

- ≤ 2.0x: synthesized by Microsoft, best quality
- 2.5x – 3.5x: player-side speed-up, slightly robotic
- 4.0x – 5.0x: noticeably fast, good for skimming

## Why it broke

**1) Token format changed**

Old code:

```
Sec-MS-GEC = SHA256(ticks + TrustedClientToken)        # 64 hex chars
Sec-MS-GEC-Version: 1-143.0.3650.75
```

What Microsoft requires now:

```
Sec-MS-GEC = "<16 hex CS64>-<64 hex SHA256>"
Sec-MS-GEC-Version: 2-…
```

Timeline: 403s started around Oct 10, 2026; upstream [edge-tts](https://github.com/rany2/edge-tts) fixed it on Oct 13 (PR #492). Every client built on older edge-tts broke at the same time — not just this app.

**2) SSML rate is capped**

Measured on the same sentence (24 kHz / 48 kbps CBR, duration derived from byte count):

| `rate` | Duration |
|---|---|
| `+0%` | 8.71 s |
| `+50%` | 5.83 s |
| `+100%` | **4.39 s** |
| `+150%` | 4.39 s |
| `+200%` | 4.39 s |

Values above `+100%` are silently ignored by the service, which is why "5x" sounded identical to "2x". That is why this fork pushes everything above 2x to the player.

## Building

### GitHub Actions (no local setup)

1. Fork this repo
2. **Actions** → **Build APK** → **Run workflow**
3. Wait 10–20 minutes, then download the artifact from the run page

### Local

```bash
git clone https://github.com/<your-name>/ddo-tts.git
cd ddo-tts
./gradlew assembleAppDebug
# output: app/build/outputs/apk/app/debug/*.apk
```

Requires **JDK 17** and **Android SDK** (platform 36 / build-tools 36).

## File map

| File | Purpose |
|---|---|
| `app/src/main/java/io/legado/app/service/SecMsGec.kt` | Sec-MS-GEC v2 token generator (CS64 + SHA256) |
| `app/src/main/java/io/legado/app/service/EdgeSpeed.kt` | Speed math: SSML ≤2x + player speed-up |
| `app/src/main/java/io/legado/app/service/EdgeSpeakFetch.kt` | Edge read-aloud WebSocket client |
| `app/src/main/java/io/legado/app/service/TTSEdgeAloudService.kt` | Read-aloud service (cache, playback, progress) |
| `app/src/main/java/io/legado/app/ui/book/read/config/SpeakEngineDialog.kt` | Engine / voice / speed settings UI |
| `app/src/test/java/io/legado/app/SecMsGecTest.kt` | Token test vectors (run by CI) |
| `app/src/test/java/io/legado/app/EdgeSpeedTest.kt` | Speed math tests |

## Credits

- Upstream app: [wangz-code/ddo-tts](https://github.com/wangz-code/ddo-tts)
- Token algorithm: [rany2/edge-tts](https://github.com/rany2/edge-tts) (PR #492, packet-capture verified)
