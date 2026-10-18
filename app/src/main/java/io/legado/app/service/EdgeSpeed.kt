package io.legado.app.service

/**
 * Edge 朗读语速换算。
 *
 * 微软 SSML 的 rate 上限就是 +100%（2 倍），传 +200% / +500% 效果和 +100% 一模一样，
 * 这就是「调到 5 倍听起来却只有 2 倍」的原因（实测 +100% / +150% / +200% 输出字节数完全相同）。
 * 所以超过 2 倍的部分改由播放器倍速补齐：
 *
 *   实际倍数 = SSML 语速（≤2x） × ExoPlayer 倍速（≤2x） → 最高约 4 倍
 */
object EdgeSpeed {

    const val DEFAULT_SCALE = 1.5f
    const val MIN_SCALE = 1.0f
    const val MAX_SCALE = 4.0f

    /**
     * 目标总倍数：界面倍数 × App 自带语速微调（speechRate 以 12 为基准）。
     */
    fun totalFactor(speechRate: Int, scale: Float): Double {
        val base = 1.0 + (speechRate - 12) / 100.0
        return (base * scale.coerceIn(MIN_SCALE, MAX_SCALE)).coerceIn(0.5, 4.0)
    }

    /** 交给 SSML 的百分比，最大 +100（超过会被服务端忽略，所以必须钳住） */
    fun ssmlPercent(speechRate: Int, scale: Float): Int {
        val total = totalFactor(speechRate, scale)
        return ((total - 1.0) * 100).toInt().coerceIn(-50, 100)
    }

    /** 剩余部分交给播放器，1.0 表示不需要额外加速 */
    fun playerSpeed(speechRate: Int, scale: Float): Float {
        val total = totalFactor(speechRate, scale)
        val ssmlFactor = 1.0 + ssmlPercent(speechRate, scale) / 100.0
        return (total / ssmlFactor).coerceIn(1.0, 3.0).toFloat()
    }

    /** 给 SSML 用的带符号百分比字符串 */
    fun ssmlRate(percent: Int): String = if (percent > 0) "+$percent%" else "$percent%"
}
