package com.muedsa.js.runtime

/**
 * IEEE-754 binary32（单精度）位模式工具。
 *
 * 背景：Kotlin/JS 的 `Float` 实际以双精度 Number 存储，`Double.toFloat()` 是恒等
 * 转换，不会执行 binary32 舍入，因此 `Math.fround` 在 JS 目标上会退化为恒等。
 * 本对象只对 Double 的 IEEE-754 位模式做整数运算，不依赖 `toFloat()`，
 * 从而在 JVM / JS / Wasm / Native 上得到完全一致的 roundTiesToEven 结果。
 *
 * 覆盖规范要求的全部特殊值：
 * - NaN 统一返回规范静默 NaN（保留符号位、载荷清零）；
 * - ±0 与 ±Infinity 原样保留；
 * - 次正规数按 binary32 的最小单位 2^-149 舍入；
 * - 超出 binary32 最大有限值的数舍入为 ±Infinity。
 *
 * 位模式约定：`Int` 的 bit 31 是符号位，bit 30..23 是指数域，bit 22..0 是尾数域；
 * `Long` 的 bit 63 是符号位，bit 62..52 是指数域，bit 51..0 是尾数域。
 */
internal object Float32Math {

    /** binary32 尾数位数。 */
    private const val MANTISSA_BITS = 23

    /** binary32 指数偏置。 */
    private const val EXPONENT_BIAS = 127

    /** binary32 指数域全 1（保留给 Infinity / NaN）。 */
    private const val EXPONENT_ALL_ONES = 0xFF

    /** binary32 最小正规数的指数（2^-126）。 */
    private const val MIN_NORMAL_EXPONENT = -126

    /** binary32 次正规数最小单位的指数（2^-149）。 */
    private const val MIN_SUBNORMAL_EXPONENT = -149

    /** binary32 尾数域掩码。 */
    private const val MANTISSA_MASK = 0x007FFFFF

    /** binary32 指数域掩码。 */
    private const val EXPONENT_MASK = 0x7F800000

    /** 规范静默 NaN：指数全 1，尾数最高位为 1、其余为 0。 */
    private const val CANONICAL_NAN_BITS = 0x7FC00000

    /** double 尾数位数。 */
    private const val DOUBLE_MANTISSA_BITS = 52

    /** double 的规范静默 NaN 位模式（符号位另加）。 */
    private const val DOUBLE_CANONICAL_NAN_BITS = 0x7FF8000000000000L

    /** double 指数偏置。 */
    private const val DOUBLE_EXPONENT_BIAS = 1023

    /** double 指数域全 1（保留给 Infinity / NaN）。 */
    private const val DOUBLE_EXPONENT_ALL_ONES = 0x7FF

    /** double 尾数域掩码。 */
    private const val DOUBLE_MANTISSA_MASK = 0x000FFFFFFFFFFFFFL

    /** double 正规数隐含的最高位（2^52）。 */
    private const val DOUBLE_HIDDEN_BIT = 1L shl DOUBLE_MANTISSA_BITS

    /**
     * 把 double 按 roundTiesToEven 舍入为 binary32 的位模式。
     *
     * 这是 `Math.fround` 与 Float32Array/DataView 写入共用的唯一舍入入口。
     */
    fun toBits(value: Double): Int {
        val raw = value.toRawBits()
        // 符号位直接取自 double 的最高位；-0 也靠它保留
        val sign = if (raw < 0L) 1 shl 31 else 0
        val exponentBits = ((raw ushr DOUBLE_MANTISSA_BITS) and 0x7FFL).toInt()
        val mantissaBits = raw and DOUBLE_MANTISSA_MASK

        // NaN：规范允许实现选择 NaN 位模式，这里统一为规范静默 NaN；
        // Infinity：指数全 1、尾数 0
        if (exponentBits == DOUBLE_EXPONENT_ALL_ONES) {
            return if (mantissaBits != 0L) sign or CANONICAL_NAN_BITS else sign or EXPONENT_MASK
        }
        // ±0：只保留符号位
        if (exponentBits == 0 && mantissaBits == 0L) return sign
        // double 次正规数最大也小于 2^-1022，远小于 binary32 最小次正规数的一半
        // （2^-150），按 roundTiesToEven 一律舍入为 ±0
        if (exponentBits == 0) return sign

        // 把有限正规 double 写成「整数有效数 × 2^exponent」的精确形式：
        // 有效数含隐含位共 53 位，exponent 是有效数最低位的权重
        val significand = mantissaBits or DOUBLE_HIDDEN_BIT
        val exponent = exponentBits - DOUBLE_EXPONENT_BIAS - DOUBLE_MANTISSA_BITS
        // 有效数最高位的权重，即 value 的数量级约为 2^msbExponent
        val msbExponent = exponent + 63 - significand.countLeadingZeroBits()

        return if (msbExponent >= MIN_NORMAL_EXPONENT) {
            roundToNormal(sign, significand, msbExponent)
        } else {
            roundToSubnormal(sign, significand, exponent)
        }
    }

    /**
     * 把 binary32 位模式精确扩展为 double。
     *
     * binary32 的每个有限值都能被 double 精确表示，因此这里不做任何舍入：
     * 正规数直接拼接 double 的指数域与尾数域，次正规数先规格化再拼接。
     * 不使用 `Float.fromBits(...).toDouble()`，避免依赖 JS 目标的 Float 语义。
     */
    fun fromBits(bits: Int): Double {
        val negative = bits < 0
        val exponentBits = (bits ushr MANTISSA_BITS) and EXPONENT_ALL_ONES
        val mantissaBits = bits and MANTISSA_MASK

        if (exponentBits == EXPONENT_ALL_ONES) {
            return when {
                // NaN：binary32 的 NaN 载荷无法完整映射进 double 语义，这里只保留
                // 符号位并统一为规范静默 NaN；toBits 也按同样规则保留符号位，
                // 因而 Float32Array 元素「读出再写回」不会丢失 NaN 的符号
                mantissaBits != 0 -> Double.fromBits(
                    DOUBLE_CANONICAL_NAN_BITS or (if (negative) Long.MIN_VALUE else 0L)
                )

                negative -> Double.NEGATIVE_INFINITY
                else -> Double.POSITIVE_INFINITY
            }
        }

        val magnitude = if (exponentBits == 0) {
            // 次正规数（含 ±0）：值 = mantissa * 2^-149。
            // 规格化后最高位的权重是 highest - 149，double 的指数域为
            // (highest - 149) + 1023；尾数左移到 double 尾数域的高位。
            if (mantissaBits == 0) {
                0.0
            } else {
                val highest = 31 - mantissaBits.countLeadingZeroBits()
                val doubleExponent = highest + MIN_SUBNORMAL_EXPONENT + DOUBLE_EXPONENT_BIAS
                val doubleMantissa =
                    (mantissaBits.toLong() shl (DOUBLE_MANTISSA_BITS - highest)) and DOUBLE_MANTISSA_MASK
                Double.fromBits((doubleExponent.toLong() shl DOUBLE_MANTISSA_BITS) or doubleMantissa)
            }
        } else {
            // 正规数：double 指数域 = binary32 指数 - 127 + 1023，
            // binary32 尾数左移 29 位（52 - 23）补齐 double 的 52 位尾数
            val doubleExponent = exponentBits - EXPONENT_BIAS + DOUBLE_EXPONENT_BIAS
            val doubleBits = (doubleExponent.toLong() shl DOUBLE_MANTISSA_BITS) or
                (mantissaBits.toLong() shl (DOUBLE_MANTISSA_BITS - MANTISSA_BITS))
            Double.fromBits(doubleBits)
        }

        // 用算术取负可以同时正确处理 +0 -> -0 与正负号
        return if (negative) -magnitude else magnitude
    }

    /** `Math.fround` 语义：舍入为 binary32 后再以 double 返回。 */
    fun round(value: Double): Double = fromBits(toBits(value))

    /**
     * 把 binary32 正规数舍入为 24 位有效数，并处理溢出。
     *
     * [significand] 是含隐含位的整数有效数，[msbExponent] 是它最高位的权重
     * （调用方保证 >= -126）；两者足以确定待舍入的精确值。
     */
    private fun roundToNormal(sign: Int, significand: Long, msbExponent: Int): Int {
        // binary32 正规数保留 24 位有效数（1 位隐含位 + 23 位尾数）
        val bitLength = 64 - significand.countLeadingZeroBits()
        val drop = bitLength - (MANTISSA_BITS + 1)
        var rounded: Long
        var resultMsb = msbExponent
        if (drop > 0) {
            rounded = roundRightShift(significand, drop)
            // 进位可能把 24 位有效数推成 25 位（1.111…1 -> 10.000…0）
            if (rounded == (1L shl (MANTISSA_BITS + 1))) {
                rounded = rounded ushr 1
                resultMsb++
            }
        } else {
            // 有效数不足 24 位（只会发生在很小或很精确的值上），左移补齐
            rounded = significand shl (-drop)
        }
        // 超过 binary32 最大有限值（2^128 - 2^104）即溢出为 ±Infinity；
        // 恰好舍入到 2^128 的情况也会让 resultMsb 变成 128
        if (resultMsb > EXPONENT_BIAS) return sign or EXPONENT_MASK
        val biasedExponent = resultMsb + EXPONENT_BIAS
        return sign or (biasedExponent shl MANTISSA_BITS) or (rounded.toInt() and MANTISSA_MASK)
    }

    /**
     * 把 binary32 次正规数舍入到最小单位 2^-149 的整数倍。
     *
     * 精确值除以 2^-149 后按 roundTiesToEven 取整；进位到 2^23 时恰好是
     * 最小正规数 2^-126，其位模式（指数域 1、尾数 0）与整数 2^23 一致。
     */
    private fun roundToSubnormal(sign: Int, significand: Long, exponent: Int): Int {
        val shift = exponent - MIN_SUBNORMAL_EXPONENT
        val units = if (shift >= 0) {
            significand shl shift
        } else {
            roundRightShift(significand, -shift)
        }
        return sign or units.toInt()
    }

    /**
     * 把非负整数 [value] 右移 [shift] 位并按 roundTiesToEven 舍入。
     *
     * [value] 最多 53 位，因此 [shift] 超过 63 时结果必为 0；
     * 1..63 范围内用整数比较判断「过半 / 恰好一半 / 不足一半」。
     */
    private fun roundRightShift(value: Long, shift: Int): Long {
        if (shift <= 0) return value shl (-shift)
        if (shift > 63) return 0
        val quotient = value ushr shift
        val remainder = value and ((1L shl shift) - 1L)
        val halfway = 1L shl (shift - 1)
        return if (remainder > halfway || (remainder == halfway && (quotient and 1L) != 0L)) {
            quotient + 1L
        } else {
            quotient
        }
    }
}
