package io.legado.app.service

/**
 * Sec-MS-GEC v2 token generator for Microsoft Edge "read aloud".
 *
 * 微软在 2026 年 10 月把 Edge 朗读接口的 token 换成了 v2：
 *   v1: Sec-MS-GEC = SHA256(ticks + TrustedClientToken) 的 64 位 hex
 *   v2: Sec-MS-GEC = "<16 位 CS64>-<64 位 SHA256>"，且 Sec-MS-GEC-Version 必须是 "2-x.y.z"
 * 继续用 v1 会在 WebSocket 握手时被 403，这就是内置 Edge 朗读失效的原因。
 *
 * 本文件是 rany2/edge-tts (src/edge_tts/drm.py) 的 Kotlin 移植，
 * 已用 Edge 155 抓包得到的 token 向量校验。
 */
@OptIn(ExperimentalUnsignedTypes::class)
object SecMsGec {

    /** Sec-MS-GEC-Version，必须和 v2 token 一起使用，前缀 "2-" 不能少。 */
    const val VERSION = "2-143.0.3650.75"

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH = 11644473600.0
    private const val S_TO_NS = 1e9

    private const val HEX = "0123456789ABCDEF"
    private const val SHA256_BLOCK_CONSTANT_ADJUSTMENT = 0xC9DC06A7u
    private const val SIGMA0_ADJUSTMENT = 0x059C0B80u
    private const val SIGMA1_ADJUSTMENT = 0x000B92C0u
    private const val CS_FACTOR = 0x82893933u
    private const val CS_ADJUSTMENT0 = 0xF2F90B1Au
    private const val CS_ADJUSTMENT1 = 0x0B1A0000u

    private val SHA256_INITIAL = uintArrayOf(
        0x6A09E667u, 0xBB67AE85u, 0x3C6EF372u, 0xA54FF53Au,
        0x510E527Fu, 0x9B05688Cu, 0x1F83D9ABu, 0x5BE0CD19u
    )

    private val SHA256_STATE = uintArrayOf(
        0xFD9FBEA2u, 0x91243CE7u, 0xBE499094u, 0xCD2E1E0Au,
        0x6C006F49u, 0x04BD35ABu, 0xFEB0A7E4u, 0x03EFFBD2u
    )

    private val SHA256_SCHEDULE = uintArrayOf(
        0xEB24BF9Eu, 0x1558691Fu, 0xA0C09EE2u, 0x0CFA32BAu,
        0xA622EE8Cu, 0xD1DD9491u, 0xC0DD25A0u, 0xF9E70AB6u,
        0xF0C31573u, 0x7058CE4Eu, 0x7188E606u, 0x00E5AB33u,
        0xD258B060u, 0x72766564u
    )

    private val SHA256_ROUND_CONSTANTS = uintArrayOf(
        0x428A2F98u, 0x71374491u, 0xB5C0FBCFu, 0xE9B5DBA5u,
        0x3956C25Bu, 0x59F111F1u, 0x923F82A4u, 0xAB1C5ED5u,
        0xD807AA98u, 0x12835B01u, 0x243185BEu, 0x550C7DC3u,
        0x72BE5D74u, 0x80DEB1FEu, 0x9BDC06A7u, 0xC19BF174u,
        0xE49B69C1u, 0xEFBE4786u, 0x0FC19DC6u, 0x240CA1CCu,
        0x2DE92C6Fu, 0x4A7484AAu, 0x5CB0A9DCu, 0x76F988DAu,
        0x983E5152u, 0xA831C66Du, 0xB00327C8u, 0xBF597FC7u,
        0xC6E00BF3u, 0xD5A79147u, 0x06CA6351u, 0x14292967u,
        0x27B70A85u, 0x2E1B2138u, 0x4D2C6DFCu, 0x53380D13u,
        0x650A7354u, 0x766A0ABBu, 0x81C2C92Eu, 0x92722C85u,
        0xA2BFE8A1u, 0xA81A664Bu, 0xC24B8B70u, 0xC76C51A3u,
        0xD192E819u, 0xD6990624u, 0xF40E3585u, 0x106AA070u,
        0x19A4C116u, 0x1E376C08u, 0x2748774Cu, 0x34B0BCB5u,
        0x391C0CB3u, 0x4ED8AA4Au, 0x5B9CCA4Fu, 0x682E6FF3u,
        0x748F82EEu, 0x78A5636Fu, 0x84C87814u, 0x8CC70208u,
        0x90BEFFFAu, 0xA4506CEBu, 0xBEF9A3F7u, 0xC67178F2u
    )

    private val SHA256_BLOCK_CONSTANTS = SHA256_ROUND_CONSTANTS.copyOf().also {
        it[14] = SHA256_BLOCK_CONSTANT_ADJUSTMENT
    }

    private val CS_COEFFICIENTS = arrayOf(
        uintArrayOf(0xE82E91B1u, 0xB32700D0u),
        uintArrayOf(0x44DE24C6u, 0x88DFF51Du),
        uintArrayOf(0x3BEB65BCu, 0x29514038u),
        uintArrayOf(0xC89C5933u, 0xB328CBDFu),
        uintArrayOf(0x8D1EC211u, 0xD405B4D5u),
        uintArrayOf(0x314EF5D5u, 0x26E55F3Cu),
        uintArrayOf(0xE92491B1u, 0x607801C6u),
        uintArrayOf(0xF0A5B9D5u, 0x82B2C1FBu),
        uintArrayOf(0x06064CF3u, 0xFA51A3DAu),
        uintArrayOf(0xDA79FA60u, 0x207CC464u),
        uintArrayOf(0xBED20560u, 0x3C3C74F3u),
        uintArrayOf(0x89F9C9F8u, 0xD2D14B61u),
        uintArrayOf(0x35FA38C6u, 0xBB04A2C1u),
        uintArrayOf(0xCB49E3C6u, 0xC5626167u)
    )

    /**
     * 生成 Sec-MS-GEC v2 token。
     * @param unixTimestamp 秒级时间戳（可传入修正过时钟偏差的时间）
     */
    fun generate(unixTimestamp: Double = System.currentTimeMillis() / 1000.0): String {
        var ticks = unixTimestamp + WIN_EPOCH
        ticks -= ticks % 300.0          // 向下取整到 5 分钟窗口
        ticks *= S_TO_NS / 100.0        // 转成 100ns 单位（Windows file time）
        val data = (Math.rint(ticks).toLong().toString() + TRUSTED_CLIENT_TOKEN)
            .toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val cs64 = cs64Digest(data, digest)
        val sb = StringBuilder(81)
        sb.append(hexLe(cs64[0])).append(hexLe(cs64[1])).append('-')
        for (b in digest) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0F]).append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    /** 自检：与 Edge 155 抓包得到的向量对比，返回 true 表示算法正确。 */
    fun selfTest(): Boolean = generate(1791888000.0) ==
            "492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876"

    private fun rol16(value: UInt): UInt = (value shl 16) or (value shr 16)

    private fun sigma0(value: UInt): UInt =
        (value shr 7) xor (value shl 25) xor (value shr 18) xor (value shl 14) xor (value shr 3)

    private fun sigma1(value: UInt): UInt =
        (value shr 17) xor (value shl 15) xor (value shr 19) xor (value shl 13) xor (value shr 10)

    private fun rounds(state: UIntArray, words: UIntArray, firstRound: Int): UIntArray {
        val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (i in firstRound until 64) {
            val rotE = e.toULong() * 0x100000001UL
            val rotA = a.toULong() * 0x100000001UL
            val t1 = h.toULong() +
                    ((rotE shr 6) xor (rotE shr 11) xor (rotE shr 25)) +
                    (g xor (e and (f xor g))).toULong() +
                    constants[i].toULong() + words[i].toULong()
            val t2 = ((rotA shr 2) xor (rotA shr 13) xor (rotA shr 22)) +
                    ((a and b) or (c and (a or b))).toULong()
            h = g
            g = f
            f = e
            e = (d.toULong() + t1).toUInt()
            d = c
            c = b
            b = a
            a = (t1 + t2).toUInt()
        }
        return uintArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(words: UIntArray, firstWord: Int) {
        for (i in firstWord until 64) {
            val x = words[i - 2]
            val y = words[i - 15]
            words[i] = sigma1(x) + words[i - 7] + sigma0(y) + words[i - 16]
        }
    }

    private fun sha256Init(d: ByteArray): UIntArray {
        val w = UIntArray(64)
        w[14] = (((d[0].toInt() and 0xFF) shl 16) or
                ((d[1].toInt() and 0xFF) shl 8) or
                (d[2].toInt() and 0xFF)).toUInt()
        w[15] = (((d[3].toInt() and 0xFF) shl 24) or
                ((d[4].toInt() and 0xFF) shl 16) or
                ((d[5].toInt() and 0xFF) shl 8) or
                (d[6].toInt() and 0xFF)).toUInt()
        for (i in 16 until 30) {
            var value = SHA256_SCHEDULE[i - 16]
            if (i - 2 >= 14) {
                value += if (i == 16) (sigma1(w[i - 2]) xor SIGMA1_ADJUSTMENT) else sigma1(w[i - 2])
            }
            if (i - 7 >= 14) {
                value += w[i - 7]
            }
            if (i - 15 >= 14) {
                value += if (i == 29) (sigma0(w[i - 15]) xor SIGMA0_ADJUSTMENT) else sigma0(w[i - 15])
            }
            w[i] = value
        }
        w[30] = sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] +
                SHA256_BLOCK_CONSTANT_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
        expandSchedule(w, 31)
        val working = rounds(SHA256_STATE, w, 14)
        return UIntArray(8) { SHA256_INITIAL[it] + working[it] }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt must contain at least seven bytes" }
        val length = 57 + data.size
        val body = ArrayList<Byte>(length + 80)
        for (i in 7 until data.size) body.add(data[i])
        body.add(0x80.toByte())
        val pad = ((55 - length) % 64 + 64) % 64
        repeat(pad) { body.add(0) }
        val bits = length.toLong() * 8L
        for (i in 7 downTo 0) {
            body.add(((bits ushr (i * 8)) and 0xFF).toByte())
        }
        val padded = body.toByteArray()
        var state = sha256Init(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < padded.size) {
            val words = UIntArray(64)
            for (i in 0 until 16) {
                val p = offset + i * 4
                words[i] = (((padded[p].toInt() and 0xFF) shl 24) or
                        ((padded[p + 1].toInt() and 0xFF) shl 16) or
                        ((padded[p + 2].toInt() and 0xFF) shl 8) or
                        (padded[p + 3].toInt() and 0xFF)).toUInt()
            }
            expandSchedule(words, 16)
            val working = rounds(state, words, 0)
            state = UIntArray(8) { state[it] + working[it] }
            offset += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            for (j in 0 until 4) {
                out[i * 4 + j] = ((state[i] shr (24 - j * 8)).toInt() and 0xFF).toByte()
            }
        }
        return out
    }

    private fun wordsLe4Even(data: ByteArray): List<UInt> {
        val paddedSize = (data.size + 3) / 4 * 4
        val words = ArrayList<UInt>(paddedSize / 4 + 1)
        var i = 0
        while (i < paddedSize) {
            val b0 = if (i < data.size) data[i].toInt() and 0xFF else 0
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            val b3 = if (i + 3 < data.size) data[i + 3].toInt() and 0xFF else 0
            words.add((b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)).toUInt())
            i += 4
        }
        if (words.size % 2 != 0) words.add(0u)
        return words
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): UIntArray {
        var keyA = (digest[0].toInt() and 0xFF) or
                ((digest[1].toInt() and 0xFF) shl 8) or
                ((digest[2].toInt() and 0xFF) shl 16) or
                ((digest[3].toInt() and 0xFF) shl 24)
        var keyB = (digest[4].toInt() and 0xFF) or
                ((digest[5].toInt() and 0xFF) shl 8) or
                ((digest[6].toInt() and 0xFF) shl 16) or
                ((digest[7].toInt() and 0xFF) shl 24)
        keyA = keyA or 1
        keyB = keyB or 1
        val ka = keyA.toUInt()
        val kb = keyB.toUInt()
        val factors = uintArrayOf(ka * CS_FACTOR, kb * CS_FACTOR)

        val terms = ArrayList<UInt>(80)
        val offsets = ArrayList<UInt>(80)
        for (i in 0 until 14) {
            terms.add(CS_COEFFICIENTS[i][0] * factors[i % 2])
            offsets.add(CS_COEFFICIENTS[i][1])
        }

        val raw = ByteArray(data.size + 1)
        System.arraycopy(data, 0, raw, 1, data.size)
        val words = wordsLe4Even(raw)
        for (idx in words.indices) {
            val word = words[idx]
            var term = word * (if (idx % 2 == 0) ka else kb)
            var offset = rol16(word) * (if (idx % 2 == 0) 0xEF0569FBu else 0xC31713DBu)
            if (idx == 0) {
                term += CS_ADJUSTMENT0 * factors[0]
                offset += CS_ADJUSTMENT1
            }
            terms.add(term)
            offsets.add(offset)
        }

        var swapAcc = 0u
        var swapLast = 0u
        var state = 0u
        var acc = 0u
        var i = 0
        while (i < terms.size) {
            val x = terms[i]
            val y = terms[i + 1]
            var t = rol16(offsets[i] + x)
            var q = t * 0x76F50F34u
            swapAcc += rol16(q) * 0xC3EFEA97u + q
            t = rol16(offsets[i + 1] + y)
            q = t * 0x1881CE3Cu
            swapLast = rol16(q) * 0x35BD1EC9u + q
            swapAcc += swapLast

            state = state * ka + x
            state = rol16(state) * 0xCF98B111u
            state = rol16(state) * 0x87085B9Fu
            state = rol16(state) * 0x12CEB96Du
            state = rol16(state) * 0x257E1D83u
            acc += state
            t = state * kb + y
            t = rol16(t) * 0xA27416F5u
            t = rol16(t) * 0xD38396FFu
            state = rol16(rol16(t) * 0x7C932B89u) * 0xBFA49F69u
            acc += state
            i += 2
        }
        return uintArrayOf(swapLast xor state, swapAcc xor acc)
    }

    private fun hexLe(value: UInt): String {
        val sb = StringBuilder(8)
        for (j in 0 until 4) {
            val b = ((value shr (j * 8)).toInt() and 0xFF)
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }
}
