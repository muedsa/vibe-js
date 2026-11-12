package com.muedsa.js.runtime

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * IEEE-754 binary16（Float16Array / DataView getFloat16）位模式转换。
 *
 * 写入使用 roundTiesToEven；指数/尾数完全按规范组合，避免依赖平台的
 * `Double.toFloat()` 或半精度库。NaN 统一规范化为 0x7e00，符号位保留。
 */
internal object Float16Math {

    private const val SIGN_MASK = 0x8000
    private const val EXP_MASK = 0x7C00
    private const val FRACTION_MASK = 0x03FF
    private const val TWO_POW_24 = 16777216.0

    fun fromBits(bits: Int): Double {
        val sign = if (bits and SIGN_MASK != 0) -1.0 else 1.0
        val exponent = (bits ushr 10) and 0x1F
        val fraction = bits and FRACTION_MASK
        return when (exponent) {
            0 -> if (fraction == 0) sign * 0.0 else sign * fraction / TWO_POW_24
            0x1F -> if (fraction == 0) sign * Double.POSITIVE_INFINITY else Double.NaN
            else -> sign * (1.0 + fraction / 1024.0) * 2.0.pow(exponent - 15)
        }
    }

    fun toBits(value: Double): Int {
        val sign = ((value.toRawBits() ushr 63).toInt() and 1) shl 15
        if (value.isNaN()) return sign or 0x7E00
        if (value.isInfinite()) return sign or EXP_MASK
        val magnitude = abs(value)
        if (magnitude == 0.0) return sign

        val raw = magnitude.toRawBits()
        val exponent = ((raw ushr 52) and 0x7FF).toInt() - 1023
        val mantissa = raw and ((1L shl 52) - 1)
        if (exponent < -14) {
            var fraction = roundToEven(magnitude * TWO_POW_24)
            if (fraction >= 1024L) {
                return sign or 0x0400
            }
            return sign or fraction.toInt()
        }
        if (exponent > 15) return sign or EXP_MASK

        var halfExponent = exponent + 15
        var fraction = (mantissa ushr 42).toInt()
        val remainder = mantissa and ((1L shl 42) - 1)
        val halfway = 1L shl 41
        if (remainder > halfway || (remainder == halfway && fraction and 1 != 0)) {
            fraction++
        }
        if (fraction == 1024) {
            fraction = 0
            halfExponent++
        }
        if (halfExponent >= 0x1F) return sign or EXP_MASK
        return sign or (halfExponent shl 10) or fraction
    }

    /** Math.f16round：round-to-nearest-even 后按 binary16 值返回。 */
    fun round(value: Double): Double = fromBits(toBits(value))

    private fun roundToEven(value: Double): Long {
        val floorValue = floor(value)
        val fraction = value - floorValue
        val integer = floorValue.toLong()
        return if (fraction > 0.5 || (fraction == 0.5 && integer and 1L == 1L)) {
            integer + 1
        } else {
            integer
        }
    }
}
