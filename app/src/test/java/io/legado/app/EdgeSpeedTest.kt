package io.legado.app

import io.legado.app.service.EdgeSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语速换算：拉杆值 0..45 对应界面显示 0.5x ~ 5.0x；
 * SSML 最多 2 倍，剩余由播放器补，两者相乘要等于目标倍数。
 */
class EdgeSpeedTest {

    private fun approx(a: Double, b: Double, eps: Double = 1e-6) =
        assertTrue("expected $b but was $a", Math.abs(a - b) < eps)

    @Test
    fun sliderMatchesDisplayedValue() {
        approx(EdgeSpeed.sliderFactor(5), 1.0)
        approx(EdgeSpeed.sliderFactor(0), 0.5)
        approx(EdgeSpeed.sliderFactor(25), 3.0)
        approx(EdgeSpeed.sliderFactor(45), 5.0)
    }

    @Test
    fun ssmlIsCappedAtHundredPercent() {
        // 2 倍以内完全交给 SSML，播放器不用额外加速
        assertEquals(0, EdgeSpeed.ssmlPercent(5))
        assertEquals(100, EdgeSpeed.ssmlPercent(15))
        approx(EdgeSpeed.playerSpeed(15).toDouble(), 1.0)

        // 5 倍时 SSML 只给 2 倍，剩下的交给播放器
        assertEquals(100, EdgeSpeed.ssmlPercent(45))
        approx(EdgeSpeed.playerSpeed(45).toDouble(), 2.5)
    }

    @Test
    fun splitAlwaysMultipliesBackToTarget() {
        for (value in 0..45) {
            val total = EdgeSpeed.totalFactor(value)
            val ssml = 1.0 + EdgeSpeed.ssmlPercent(value) / 100.0
            val player = EdgeSpeed.playerSpeed(value).toDouble()
            approx(ssml * player, total, 0.001)
        }
    }

    @Test
    fun outOfRangeIsClamped() {
        approx(EdgeSpeed.totalFactor(-10), 0.5)
        approx(EdgeSpeed.totalFactor(100), 5.0)
        assertEquals("+100%", EdgeSpeed.ssmlRate(100))
        assertEquals("-20%", EdgeSpeed.ssmlRate(-20))
    }
}
