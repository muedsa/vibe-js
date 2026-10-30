package com.muedsa.js.runtime.value

/**
 * 字符串原始值。
 */
class JSString(val value: String) : JSValue() {

    override val isPrimitive: Boolean = true

    override fun toPrimitiveBoolean(): Boolean = value.isNotEmpty()

    /**
     * ToNumber(String)：
     * - 空串（或仅空白）为 0；
     * - "Infinity" / "-Infinity" 为对应无穷；
     * - 0x/0o/0b 前缀按进制解析；
     * - 其余按十进制数字解析，无法解析则为 NaN。
     */
    override fun toPrimitiveNumber(): Double {
        val trimmed = value.trimJsWhitespace()
        if (trimmed.isEmpty()) return 0.0
        return when {
            trimmed == "Infinity" || trimmed == "+Infinity" -> Double.POSITIVE_INFINITY
            trimmed == "-Infinity" -> Double.NEGATIVE_INFINITY
            trimmed.startsWith("0x") || trimmed.startsWith("0X") ->
                parseRadix(trimmed.substring(2), 16)

            trimmed.startsWith("0o") || trimmed.startsWith("0O") ->
                parseRadix(trimmed.substring(2), 8)

            trimmed.startsWith("0b") || trimmed.startsWith("0B") ->
                parseRadix(trimmed.substring(2), 2)

            else -> trimmed.toDoubleOrNull() ?: Double.NaN
        }
    }

    override fun toPrimitiveString(): String = value

    /** 解析指定进制的数字；非法返回 NaN。 */
    private fun parseRadix(text: String, radix: Int): Double {
        if (text.isEmpty()) return Double.NaN
        var result = 0.0
        for (c in text) {
            val digit = digitValue(c)
            if (digit < 0 || digit >= radix) return Double.NaN
            result = result * radix + digit
        }
        return result
    }

    private fun digitValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    companion object {
        val EMPTY = JSString("")
    }
}

/**
 * 去除 ECMAScript 视为空白的字符（简化版，覆盖常见空白）。
 */
internal fun String.trimJsWhitespace(): String {
    var start = 0
    var end = length
    while (start < end && isJsWhitespace(this[start])) start++
    while (end > start && isJsWhitespace(this[end - 1])) end--
    return substring(start, end)
}

/** 判断字符是否为 JS 空白（含行终止符）。 */
internal fun isJsWhitespace(c: Char): Boolean = when (c) {
    ' ', '\t', '\r', '\n', '\u000B', '\u000C', '\u00A0', '\uFEFF',
    '\u2028', '\u2029' -> true

    else -> false
}
