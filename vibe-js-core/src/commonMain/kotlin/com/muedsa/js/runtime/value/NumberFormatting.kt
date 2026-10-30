package com.muedsa.js.runtime.value

/**
 * ECMAScript Number::toString 的简化实现。
 *
 * 目标：在不同平台（JVM / JS / Native）上得到一致的输出，并覆盖常见格式：
 * - 整数不带小数点（1.0 -> "1"）
 * - 极小/极大值使用指数形式（1e-7 -> "1e-7"，1e21 -> "1e+21"）
 * - 其余使用定点形式（0.000001、0.30000000000000004）
 *
 * 算法基于平台 Double.toString() 提供的最短十进制表示，
 * 再按 ECMAScript 的格式化规则重新排版。
 */
object NumberFormatting {

    fun toString(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value == 0.0) return "0"
        if (value == Double.POSITIVE_INFINITY) return "Infinity"
        if (value == Double.NEGATIVE_INFINITY) return "-Infinity"

        val negative = value < 0
        val raw = if (negative) (-value).toString() else value.toString()

        // 拆出尾数与指数（平台可能输出 "1.0E-6" 或 "0.000001"）
        val exponentIndex = raw.indexOfFirst { it == 'E' || it == 'e' }
        val mantissa = if (exponentIndex >= 0) raw.substring(0, exponentIndex) else raw
        val rawExponent = if (exponentIndex >= 0) {
            raw.substring(exponentIndex + 1).toIntOrNull() ?: 0
        } else {
            0
        }

        val dotIndex = mantissa.indexOf('.')

        // 去掉小数点，得到纯数字串
        var digits = mantissa.replace(".", "")

        // 去掉前导零（至少保留一位）
        var leadingZeros = 0
        while (leadingZeros < digits.length - 1 && digits[leadingZeros] == '0') {
            leadingZeros++
        }
        digits = digits.substring(leadingZeros)

        // 去掉尾随零
        digits = digits.trimEnd('0')
        if (digits.isEmpty()) return "0"

        // 计算 digits 对应的十进制指数：value = digits * 10^exp10
        val integerPartLength = if (dotIndex >= 0) dotIndex else mantissa.length
        val exp10 = rawExponent + (integerPartLength - leadingZeros) - digits.length

        // 规范中的 n：小数点位置（digits 前面有 n 位整数）
        val n = exp10 + digits.length
        val k = digits.length
        val sb = StringBuilder()
        if (negative) sb.append('-')

        when {
            // 极大/极小值使用指数形式
            n > 21 || n <= -6 -> {
                sb.append(digits[0])
                if (k > 1) {
                    sb.append('.')
                    sb.append(digits, 1, k)
                }
                val exponent = n - 1
                sb.append('e')
                sb.append(if (exponent >= 0) '+' else '-')
                sb.append(if (exponent >= 0) exponent else -exponent)
            }

            // 整数
            n >= k -> {
                sb.append(digits)
                repeat(n - k) { sb.append('0') }
            }

            // 小数，且小数点位于 digits 内部
            n > 0 -> {
                sb.append(digits, 0, n)
                sb.append('.')
                sb.append(digits, n, k)
            }

            // 0.000... 形式
            else -> {
                sb.append("0.")
                repeat(-n) { sb.append('0') }
                sb.append(digits)
            }
        }

        return sb.toString()
    }
}
