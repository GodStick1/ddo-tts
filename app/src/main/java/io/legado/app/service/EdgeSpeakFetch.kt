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

private lateinit var wss: WebSocket

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

        // 语速倍数在「朗读引擎 → Edge大声朗读 → 编辑」里调，存于 SharedPreferences。
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

        // 避免单个章节太长导致wss 长时间连接
        var count = 0
    }

    private var lastTime: Long = 0
    private var isReconnect = false
    private lateinit var lastWss: WebSocket
    private var audioOutputStream = PipedOutputStream()
    private var audioInputStream = PipedInputStream(audioOutputStream, 8192)
    private var client = OkHttpClient.Builder()
        .connectTimeout(50, TimeUnit.SECONDS)
        .readTimeout(80, TimeUnit.SECONDS)
        .writeTimeout(50, TimeUnit.SECONDS)
        .build()

    init {
        // 启动时自检一次 token 算法，logcat 出现「自检失败」说明移植有问题
        if (!SecMsGec.selfTest()) {
            Log.e(TAG, "Sec-MS-GEC 自检失败：v2 token 算法输出与已知向量不一致")
        }
    }

    private fun getWssConnect(ssml: String) {
        if (::wss.isInitialized) {
            try {
                wss.close(1000, "正常关闭")
                wss.cancel()
                Log.i(TAG, "关闭 WebsocketConnect")
            } catch (e: Exception) {
                Log.i(TAG, "关闭 WebsocketConnect Exception: $e")
                e.printStackTrace()
            }
        }
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

        // 3. 添加基础头
        BASE_HEADERS.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }
        // 4. 添加WebSocket专属头
        WSS_HEADERS.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }
        // 5. 构建最终请求
        val request = requestBuilder.build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket onOpen")
                wss = webSocket
                isReconnect = false
                sendSpeechConfig(wss)
                sendSSMLMessage(wss, ssml)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 检测turn.end并关闭流
                if (text.contains("turn.end")) {
                    Log.i(TAG, "收到turn.end 关闭流")
                    closeStreamQuietly()
                    lastTime = System.currentTimeMillis()
                    lastWss = webSocket
                } else {
                    Log.d(TAG, "收到文本消息: ${text.take(200)}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    // 处理二进制音频数据（与Golang解析逻辑一致）
                    val message = bytes.toByteArray()
                    if (message.size < 2) {
                        Log.i(TAG, "WebSocket onMessage binary message too short")
                        return
                    }

                    // 解析头部长度（前2字节big endian，与Golang一致）
                    val headerLength =
                        ((message[0].toInt() and 0xFF) shl 8) or (message[1].toInt() and 0xFF)
                    if (headerLength > message.size) {
                        Log.i(TAG, "WebSocket onMessage invalid header length")
                        return
                    }

                    // 提取音频数据（跳过头部，与Golang一致）
                    val audioData = ByteArray(message.size - headerLength - 2)
                    System.arraycopy(
                        message,
                        headerLength + 2,
                        audioData,
                        0,
                        audioData.size
                    )
                    // 音频数据
                    if (audioData.isNotEmpty()) {
                        try {
                            audioOutputStream.write(audioData)
                            audioOutputStream.flush()
                        } catch (e: IOException) {
                            Log.e(TAG, "缓存音频失败", e)
                        }
                    }

                } catch (e: Exception) {
                    Log.i(TAG, "WebSocket onMessage Catch" + e.printStackTrace())
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket onClosed$code$reason")
                isReconnect = true
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?
            ) {
                isReconnect = true
                Log.e(
                    TAG,
                    "WebSocket onFailure: $t, httpCode=${response?.code}, " +
                            "msg=${response?.message}"
                )
                // 关键：必须关闭管道，否则调用方会一直阻塞在 read() 上，
                // 表现为「点了朗读没声音也不报错」
                closeStreamQuietly()
            }

        }
        client.newWebSocket(request, listener)
    }

    private fun closeStreamQuietly() {
        try {
            audioOutputStream.close()
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

    private fun initStream() {
        try {
            audioOutputStream = PipedOutputStream()
            audioInputStream = PipedInputStream(audioOutputStream, 8192) // 缓冲区8KB
        } catch (e: Exception) {
            Log.i(TAG, "初始化管道流失败")
        }
    }


    fun synthesizeText(
        speakText: String,
        rate: Int,
        voice: String = DEFAULT_VOICE,
        speedScale: Float = EdgeSpeed.DEFAULT_SCALE
    ): InputStream {
        initStream()
        Log.i(TAG, "speakText: $speakText")
        try {
            val speakTextStr = removeSpecialCharacters(speakText)
            val currentTime = System.currentTimeMillis()
            val timeDiff = currentTime - lastTime
            // 判断是否超过毫秒
            val ssml = mkSSML(speakTextStr, voice, processRate(rate, speedScale))
            if (timeDiff < 500 && !isReconnect && count <= 20) {
                Log.i(TAG, "复用上次Wss_count=" + count)
                wss = lastWss
                count += 1
                sendSSMLMessage(wss, ssml)
            } else {
                Log.i(TAG, "重新生成Wss_count=" + count)
                count = 0
                getWssConnect(ssml)
            }
        } catch (e: Exception) {
            Log.i(TAG, "sendSSMLMessage:$e")
        }
        return audioInputStream
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
    private fun processRate(rate: Int, speedScale: Float): String {
        return EdgeSpeed.ssmlRate(EdgeSpeed.ssmlPercent(rate, speedScale))
    }

    fun release() {
        if (::lastWss.isInitialized) lastWss.cancel()
        closeStreamQuietly()
        Log.i(TAG, "管道流已关闭")
    }
}
