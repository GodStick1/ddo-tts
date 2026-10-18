package io.legado.app.service

import kotlin.math.roundToInt

/**
 * Edge 朗读语速换算。
 *
 * 朗读面板那根「朗读速度」拉杆的值域是 0..45，界面上显示为 (value + 5) / 10，
 * 也就是 0.5x ~ 5.0x。以前 Edge 只把 (value - 7)% 塞进 SSML，所以拉到底也只有 1.4 倍，
 * 看起来像「失效」。现在按界面显示的倍数真正生效：
 *
 *   SSML 最多 +100%（2 倍，微软硬上限，超过会被忽略）
 *   超过 2 倍的部分交给 ExoPlayer 倍速，保持音高
 */
object EdgeSpeed {

    const val MIN_FACTOR = 0.5
    const val MAX_FACTOR = 5.0

    /** 拉杆数值 → 界面显示的倍数（0.5 ~ 5.0） */
    fun sliderFactor(sliderValue: Int): Double =
        ((sliderValue + 5) / 10.0).coerceIn(MIN_FACTOR, MAX_FACTOR)

    /** 目标总倍数 */
    fun totalFactor(sliderValue: Int): Double = sliderFactor(sliderValue)

    /** 交给 SSML 的百分比，最大 +100（超过会被服务端忽略） */
    fun ssmlPercent(sliderValue: Int): Int =
        ((totalFactor(sliderValue) - 1.0) * 100).roundToInt().coerceIn(-50, 100)

    /** 剩余部分交给播放器，1.0 表示不需要额外加速 */
    fun playerSpeed(sliderValue: Int): Float {
        val total = totalFactor(sliderValue)
        val ssmlFactor = 1.0 + ssmlPercent(sliderValue) / 100.0
        return (total / ssmlFactor).coerceIn(1.0, 2.5).toFloat()
    }

    /** 给 SSML 用的带符号百分比字符串 */
    fun ssmlRate(percent: Int): String = if (percent > 0) "+$percent%" else "$percent%"
}
