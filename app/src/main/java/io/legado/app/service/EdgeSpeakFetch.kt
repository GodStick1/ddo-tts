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

class EdgeSpeakFetch {
    // 常量定义
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
        // 注意：微软 SSML 的 rate 上限是 +100%（2 倍），再大的值会被服务端忽略，
        // 所以 >2 倍的部分由 EdgeSpeed 换算成播放器倍速来补。

        // 基础请求头（对应Python的BASE_HEADERS）
        val BASE_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${CHROMIUM_MAJOR_VERSION}.0.0.0 Safari/537.36 Edg/${CHROMIUM_MAJOR_VERSION}.0.0.0",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        // WebSocket专属头（对应Python的WSS_HEADERS）
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

    /**
     * 每次合成都用全新的管道和全新的连接，并用 generation 丢弃过期连接上迟到的音频帧。
     *
     * 旧实现复用 WebSocket 且把输出流存在可变字段里：切换语速时会取消上一个下载任务，
     * 但旧连接仍在收包，迟到的音频被写进「下一句」的管道，于是两句声音叠在一起。
     */
    private var generation = 0
    private var currentSocket: WebSocket? = null
    private var currentOut: PipedOutputStream? = null

    init {
        // 启动时自检一次 token 算法，logcat 出现「自检失败」说明移植有问题
        if (!SecMsGec.selfTest()) {
            Log.e(TAG, "Sec-MS-GEC 自检失败：v2 token 算法输出与已知向量不一致")
        }
    }

    fun synthesizeText(
        speakText: String,
        rate: Int,
        voice: String = DEFAULT_VOICE
    ): InputStream {
        // 如果上一句还在合成（例如切换语速时取消了下载任务），先把它关掉，
        // 否则读端会一直阻塞在 read() 上，协程永远不结束
        closeQuietly(currentOut)
        val out = PipedOutputStream()
        val input = PipedInputStream(out, 8192)
        currentOut = out
        Log.i(TAG, "speakText: $speakText")
        try {
            val speakTextStr = removeSpecialCharacters(speakText)
            val ssml = mkSSML(speakTextStr, voice, processRate(rate))
            connect(out, ssml)
        } catch (e: Exception) {
            Log.e(TAG, "synthesizeText 失败: $e")
            closeQuietly(out)
        }
        return input
    }

    private fun connect(out: PipedOutputStream, ssml: String) {
        val myGeneration = ++generation

        // 关掉上一条连接，它之后送来的数据会被 generation 检查丢弃
        currentSocket?.let { old ->
            try {
                old.close(1000, "正常关闭")
                old.cancel()
                Log.i(TAG, "关闭上一条 WebsocketConnect")
            } catch (e: Exception) {
                Log.i(TAG, "关闭 WebsocketConnect Exception: $e")
            }
        }
        currentSocket = null

        val secMsGec = SecMsGec.generate(System.currentTimeMillis() / 1000.0)
        val connectionId = connectID()
        val queryParams = String.format(
            "ConnectionId=%s&Sec-MS-GEC=%s&Sec-MS-GEC-Version=%s&TrustedClientToken=%s",
            connectionId,
            secMsGec,
            SEC_MS_GEC_VERSION,
            TRUSTED_CLIENT_TOKEN
        )
        val wsUrl = String.format("wss://%s%s?%s", BASE_URL, WSS_PATH, queryParams)

        val requestBuilder = Request.Builder().url(wsUrl)
        BASE_HEADERS.forEach { (key, value) -> requestBuilder.header(key, value) }
        WSS_HEADERS.forEach { (key, value) -> requestBuilder.header(key, value) }
        val request = requestBuilder.build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myGeneration != generation) return
                Log.i(TAG, "WebSocket onOpen")
                currentSocket = webSocket
                sendSpeechConfig(webSocket)
                sendSSMLMessage(webSocket, ssml)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myGeneration != generation) return
                if (text.contains("turn.end")) {
                    Log.i(TAG, "收到 turn.end 关闭流")
                    closeQuietly(out)
                } else {
                    Log.d(TAG, "收到文本消息: ${text.take(200)}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (myGeneration != generation) {
                    Log.d(TAG, "丢弃过期连接的音频帧")
                    return
                }
                try {
                    val message = bytes.toByteArray()
                    if (message.size < 2) return
                    // 解析头部长度（前2字节big endian）
                    val headerLength =
                        ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)
                    if (headerLength > message.size) return
                    val audioData = ByteArray(message.size - headerLength - 2)
                    System.arraycopy(message, headerLength + 2, audioData, 0, audioData.size)
                    if (audioData.isNotEmpty()) {
                        try {
                            out.write(audioData)
                            out.flush()
                        } catch (e: IOException) {
                            Log.e(TAG, "缓存音频失败", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "WebSocket onMessage Catch$e")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myGeneration != generation) return
                Log.i(TAG, "WebSocket onClosed$code$reason")
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?
            ) {
                Log.e(
                    TAG,
                    "WebSocket onFailure: $t, httpCode=${response?.code}, " +
                            "msg=${response?.message}"
                )
                if (myGeneration != generation) return
                // 必须关闭管道，否则调用方会一直阻塞在 read() 上
                closeQuietly(out)
            }
        }
        currentSocket = client.newWebSocket(request, listener)
    }

    private fun closeQuietly(out: PipedOutputStream?) {
        try {
            out?.close()
        } catch (e: IOException) {
            Log.i(TAG, "关闭音频管道失败: $e")
        }
    }

    // 移除文本中的特殊字符和表情，保留常用文章标点符号
    private fun removeSpecialCharacters(text: String): String {
        val pattern = Pattern.compile("[^\\w\\s\u4e00-\u9fff，。！？；：、（）《》【】“”‘’]")
        val matcher = pattern.matcher(text)
        return matcher.replaceAll("")
    }

    // 构造SSML文本
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

    // 发送speech.config消息
    private fun sendSpeechConfig(wss: WebSocket) {
        Log.i(TAG, "准备写入sendSpeechConfig")
        val speechConfig =
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"

        // 时间格式严格匹配Golang的time.RFC1123
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = sdf.format(Date())

        val speechConfigMsg = String.format(
            "X-Timestamp:%s\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n%s\r\n",
            timestamp, speechConfig
        )
        wss.send(speechConfigMsg)
    }

    // 发送SSML消息
    private fun sendSSMLMessage(wss: WebSocket, ssml: String) {
        Log.i(TAG, "准备写入SSML")
        val requestId = connectID()
        val sdf = SimpleDateFormat("EEE MMM d yyyy HH:mm:ss zzz", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = sdf.format(Date())

        val ssmlMsg = String.format(
            "X-RequestId:%s\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:%sZ\r\nPath:ssml\r\n\r\n%s",
            requestId, timestamp, ssml
        )
        Log.i(TAG, "WebSocket sendSSMLMessage")
        wss.send(ssmlMsg)
    }

    // 生成无破折号的UUID
    private fun connectID(): String {
        return UUID.randomUUID().toString().replace("-".toRegex(), "")
    }

    // SSML 语速：最大只到 +100%，剩余倍数由播放器补（见 EdgeSpeed）
    private fun processRate(rate: Int): String {
        return EdgeSpeed.ssmlRate(EdgeSpeed.ssmlPercent(rate))
    }

    fun release() {
        generation++
        try {
            currentSocket?.close(1000, "正常关闭")
            currentSocket?.cancel()
        } catch (e: Exception) {
            Log.i(TAG, "释放 WebSocket 失败: $e")
        }
        closeQuietly(currentOut)
        Log.i(TAG, "管道流已关闭")
    }
}
