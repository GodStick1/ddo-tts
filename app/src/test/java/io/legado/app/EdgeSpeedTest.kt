package io.legado.app

import io.legado.app.service.EdgeSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语速换算：SSML 最多 2 倍，剩余由播放器补，两者相乘要等于目标倍数。
 */
class EdgeSpeedTest {

    private fun approx(a: Double, b: Double, eps: Double = 1e-6) =
        assertTrue("expected $b but was $a", Math.abs(a - b) < eps)

    @Test
    fun defaultScaleEqualsSeventyPercentBoostAtNormalRate() {
        // speechRate = 10 时基准是 0.98 倍，乘 1.5 倍 = 1.47 倍
        approx(EdgeSpeed.totalFactor(10, 1.5f), 1.47)
        assertEquals(47, EdgeSpeed.ssmlPercent(10, 1.5f))
        approx(EdgeSpeed.playerSpeed(10, 1.5f).toDouble(), 1.0)
    }

    @Test
    fun ssmlIsCappedAtHundredPercent() {
        // 4 倍时 SSML 只给 2 倍，剩下的交给播放器
        assertEquals(100, EdgeSpeed.ssmlPercent(10, 4f))
        approx(EdgeSpeed.playerSpeed(10, 4f).toDouble(), 1.96, 0.02)
        approx(EdgeSpeed.totalFactor(10, 4f), 3.92)
    }

    @Test
    fun splitAlwaysMultipliesBackToTarget() {
        for (rate in 5..15) {
            for (step in 10..40) {
                val scale = step / 10f
                val total = EdgeSpeed.totalFactor(rate, scale)
                val ssml = 1.0 + EdgeSpeed.ssmlPercent(rate, scale) / 100.0
                val player = EdgeSpeed.playerSpeed(rate, scale).toDouble()
                approx(ssml * player, total, 0.001)
            }
        }
    }

    @Test
    fun scaleIsClamped() {
        approx(EdgeSpeed.totalFactor(10, 0.1f), EdgeSpeed.totalFactor(10, 1f))
        approx(EdgeSpeed.totalFactor(10, 99f), EdgeSpeed.totalFactor(10, 4f))
        assertEquals("+100%", EdgeSpeed.ssmlRate(100))
        assertEquals("-7%", EdgeSpeed.ssmlRate(-7))
    }
}
