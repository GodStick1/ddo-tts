package io.legado.app.service

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Edge 朗读 WebSocket 客户端。
 *
 * 连接策略（Safe Reuse）：
 * - 一句正常结束（收到 turn.end）后，连接保留，下一句直接复用（省掉 ~300ms 握手）
 * - 如果上一句还在合成时就被打断（切换语速/翻段），这条连接上还有它的数据，
 *   直接作废换新连接，避免两句话的音频串在一起
 * - 每句都有自己独立的管道，音频只会写进发起这句时的那条管道
 */
class EdgeSpeakFetch {
    companion object {
        private const val TAG = "EdgeSpeakFetch"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val BASE_URL = "speech.platform.bing.com"
        private const val WSS_PATH = "/consumer/speech/synthesize/readaloud/edge/v1"
        private const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private var CHROMIUM_MAJOR_VERSION: String = CHROMIUM_FULL_VERSION.split(".", limit = 2)[0]

        // 微软 2026-10 起强制 v2 token，版本号前缀必须是 2-，且 token 必须是 v2 格式，
        // 否则 WebSocket 握手直接 403（这就是内置 Edge 朗读失效的原因）。
        private const val SEC_MS_GEC_VERSION: String = SecMsGec.VERSION

        // 语速倍数在「朗读引擎 → Edge大声朗读 → 编辑」里调，存于 AppConfig.ttsSpeechRate。
        // 微软 SSML 的 rate 上限是 +100%（2 倍），超过的部分由 EdgeSpeed 换算成播放器倍速。

        val BASE_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROMIUM_MAJOR_VERSION}.0.0.0 Safari/537.36 Edg/${CHROMIUM_MAJOR_VERSION}.0.0.0",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        val WSS_HEADERS = mapOf(
            "Pragma" to "no-cache",
            "Cache-Control" to "no-cache",
            "Origin" to "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold",
            "Sec-WebSocket-Version" to "13"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(50, TimeUnit.SECONDS)
        .readTimeout(80, TimeUnit.SECONDS)
        .writeTimeout(50, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    private var socket: WebSocket? = null
    private var socketUsable = false          // 上一句是否「正常结束」，可直接复用
    private var inFlight = false              // 当前是否有一句正在合成
    private var activeOut: PipedOutputStream? = null

    init {
        if (!SecMsGec.selfTest()) {
            Log.e(TAG, "Sec-MS-GEC 自检失败：v2 token 算法输出与已知向量不一致")
        }
    }

    fun synthesizeText(
        speakText: String,
        rate: Int,
        voice: String = DEFAULT_VOICE
    ): InputStream {
        val out = PipedOutputStream()
        val input = PipedInputStream(out, 8192)
        Log.i(TAG, "speakText: $speakText")
        try {
            val ssml = mkSSML(removeSpecialCharacters(speakText), voice, processRate(rate))
            synchronized(lock) {
                // 上一句还没结束就被打断：同一条连接上还有它的数据，必须换新连接
                if (inFlight) {
                    Log.i(TAG, "上一句未结束，作废当前连接")
                    socketUsable = false
                    socket?.cancel()
                    socket = null
                    inFlight = false
                }
                closeActiveOutLocked()
                activeOut = out
                inFlight = true

                val reusable = socketUsable && socket != null
                socketUsable = false

                if (reusable) {
                    val ws = socket!!
                    if (sendSSMLMessage(ws, ssml)) {
                        Log.i(TAG, "复用已有 WebSocket")
                        return input
                    }
                    // 连接可能已被服务端关掉，发送失败就换新连接
                    Log.i(TAG, "复用失败，重新连接")
                    ws.cancel()
                    socket = null
                }
                openLocked(ssml)
            }
        } catch (e: Exception) {
            Log.e(TAG, "synthesizeText 失败: $e")
            synchronized(lock) {
                inFlight = false
                socketUsable = false
                closeActiveOutLocked()
            }
        }
        return input
    }

    /** 新建连接，返回时 socket 已赋值；SSML 在 onOpen 里发出 */
    private fun openLocked(ssml: String) {
        val secMsGec = SecMsGec.generate(System.currentTimeMillis() / 1000.0)
        val connectionId = connectID()
        val queryParams = String.format(
            "ConnectionId=%s&Sec-MS-GEC=%s&Sec-MS-GEC-Version=%s&TrustedClientToken=%s",
            connectionId, secMsGec, SEC_MS_GEC_VERSION, TRUSTED_CLIENT_TOKEN
        )
        val wsUrl = String.format("wss://%s%s?%s", BASE_URL, WSS_PATH, queryParams)

        val requestBuilder = Request.Builder().url(wsUrl)
        BASE_HEADERS.forEach { (key, value) -> requestBuilder.header(key, value) }
        WSS_HEADERS.forEach { (key, value) -> requestBuilder.header(key, value) }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    if (webSocket !== socket) return
                    Log.i(TAG, "WebSocket onOpen")
                    sendSpeechConfig(webSocket)
                    sendSSMLMessage(webSocket, ssml)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("turn.end")) {
                    synchronized(lock) {
                        if (webSocket !== socket) return
                        Log.i(TAG, "收到 turn.end，连接可复用")
                        closeActiveOutLocked()
                        inFlight = false
                        socketUsable = true
                    }
                } else {
                    Log.d(TAG, "收到文本消息: ${text.take(200)}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 写管道可能阻塞（读端还没消费），所以不要拿着锁写
                val out = synchronized(lock) {
                    if (webSocket !== socket || activeOut == null) return
                    activeOut
                } ?: return
                try {
                    val message = bytes.toByteArray()
                    if (message.size < 2) return
                    val headerLength =
                        ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)
                    if (headerLength > message.size) return
                    val audioData = ByteArray(message.size - headerLength - 2)
                    System.arraycopy(message, headerLength + 2, audioData, 0, audioData.size)
                    if (audioData.isNotEmpty()) {
                        out.write(audioData)
                        out.flush()
                    }
                } catch (e: IOException) {
                    // 管道已被关闭（这句被取消/被下一句顶掉），丢弃即可
                    Log.d(TAG, "音频写入被中止: $e")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    Log.i(TAG, "WebSocket onClosed $code $reason")
                    if (webSocket !== socket) return
                    closeActiveOutLocked()
                    inFlight = false
                    socketUsable = false
                    socket = null
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(
                    TAG,
                    "WebSocket onFailure: $t, httpCode=${response?.code}, msg=${response?.message}"
                )
                synchronized(lock) {
                    if (webSocket !== socket) return
                    closeActiveOutLocked()
                    inFlight = false
                    socketUsable = false
                    socket = null
                }
            }
        }
        socket = client.newWebSocket(requestBuilder.build(), listener)
    }

    private fun closeActiveOutLocked() {
        try {
            activeOut?.close()
        } catch (e: IOException) {
            Log.d(TAG, "关闭音频管道失败: $e")
        }
        activeOut = null
    }

    private fun removeSpecialCharacters(text: String): String {
        val pattern = Pattern.compile("[^\\w\\s\u4e00-\u9fff，。！？；：、（）《》【】“”‘’]")
        return pattern.matcher(text).replaceAll("")
    }

    private fun mkSSML(
        text: String,
        voice: String,
        rate: String,
        pitch: String = "+0Hz",
        volume: String = "+0%"
    ): String {
        return String.format(
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                    "<voice name='%s'>" +
                    "<prosody pitch='%s' rate='%s' volume='%s'>%s</prosody>" +
                    "</voice>" +
                    "</speak>",
            voice, pitch, rate, volume, text
        )
    }

    private fun sendSpeechConfig(wss: WebSocket): Boolean {
        Log.i(TAG, "发送 speech.config")
        val speechConfig =
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = sdf.format(Date())
        val msg = String.format(
            "X-Timestamp:%s\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n%s\r\n",
            timestamp, speechConfig
        )
        return wss.send(msg)
    }

    private fun sendSSMLMessage(wss: WebSocket, ssml: String): Boolean {
        val requestId = connectID()
        val sdf = SimpleDateFormat("EEE MMM d yyyy HH:mm:ss zzz", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = sdf.format(Date())
        val ssmlMsg = String.format(
            "X-RequestId:%s\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:%sZ\r\nPath:ssml\r\n\r\n%s",
            requestId, timestamp, ssml
        )
        Log.i(TAG, "发送 SSML")
        return wss.send(ssmlMsg)
    }

    private fun connectID(): String =
        UUID.randomUUID().toString().replace("-".toRegex(), "")

    private fun processRate(rate: Int): String =
        EdgeSpeed.ssmlRate(EdgeSpeed.ssmlPercent(rate))

    fun release() {
        synchronized(lock) {
            socketUsable = false
            inFlight = false
            try {
                socket?.close(1000, "正常关闭")
                socket?.cancel()
            } catch (e: Exception) {
                Log.i(TAG, "释放 WebSocket 失败: $e")
            }
            socket = null
            closeActiveOutLocked()
        }
        Log.i(TAG, "连接已释放")
    }
}
