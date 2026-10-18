package io.legado.app

import io.legado.app.service.SecMsGec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sec-MS-GEC v2 token 校验。
 * 期望值来自 Edge 155 的抓包（见 rany2/edge-tts PR #492），
 * 只要这组向量对得上，token 算法就是对的。
 */
class SecMsGecTest {

    @Test
    fun matchesCapturedEdgeTokens() {
        assertEquals(
            "492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
            SecMsGec.generate(1791888000.0)
        )
        assertEquals(
            "FDA8112B8756B195-BA3F4ED428AF8E21948543EAD66D831CEAAFE0A00E5471312ECDDC3C7422E611",
            SecMsGec.generate(1780000000.0)
        )
        assertEquals(
            "6D50DDDB91812C33-DD68EFB01898B0750C9797D51A5FF93471DFDAE6888A1039D532CBDCCE54545D",
            SecMsGec.generate(1760000000.0)
        )
        assertEquals(
            "99E54519B490BE5C-E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654",
            SecMsGec.generate(1800000000.0)
        )
        assertEquals(
            "61510DCA2E7DC9EE-A6638705A5A06AF53B44A40C5883F6FBA2970F1A4CCBFD94820FE89E99F58976",
            SecMsGec.generate(1791889500.0)
        )
    }

    @Test
    fun currentTokenHasV2Shape() {
        val token = SecMsGec.generate()
        assertTrue(
            "token 应为 <16 hex>-<64 hex>，实际：$token",
            Regex("^[0-9A-F]{16}-[0-9A-F]{64}$").matches(token)
        )
        assertEquals("2-143.0.3650.75", SecMsGec.VERSION)
        assertTrue(SecMsGec.selfTest())
    }
}
