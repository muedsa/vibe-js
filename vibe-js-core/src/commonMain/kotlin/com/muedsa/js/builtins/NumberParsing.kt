package com.muedsa.js.builtins

import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.trimJsWhitespace

/**
 * parseInt / parseFloat 的共享实现。
 *
 * 全局函数与 Number.parseInt / Number.parseFloat 使用同一套逻辑，
 * 避免像旧实现那样出现两份不一致的行为。
 */
internal object NumberParsing {

    /**
     * parseInt(string, radix)。
     *
     * 规则要点：
     * - 跳过前导空白；
     * - 支持正负号；
     * - radix 为 0/省略时根据前缀（0x）推断，否则默认 10；
     * - 只解析连续合法数字，遇到非法字符停止；
     * - 没有任何合法数字时返回 NaN。
     */
    suspend fun parseInt(interpreter: Interpreter, args: List<JSValue>): JSValue {
        val input = interpreter.toStringValue(args.getOrElse(0) { com.muedsa.js.runtime.value.JSUndefined })
            .trimJsWhitespace()

        var text = input
        var sign = 1.0
        if (text.startsWith("+")) {
            text = text.substring(1)
        } else if (text.startsWith("-")) {
            sign = -1.0
            text = text.substring(1)
        }

        val radixArg = args.getOrElse(1) { com.muedsa.js.runtime.value.JSUndefined }
        var radix = if (radixArg is com.muedsa.js.runtime.value.JSUndefined) {
            0
        } else {
            interpreter.toNumber(radixArg).toInt()
        }

        var stripHexPrefix = false
        if (radix == 0) {
            if (text.length >= 2 && text[0] == '0' && (text[1] == 'x' || text[1] == 'X')) {
                radix = 16
                stripHexPrefix = true
            } else {
                radix = 10
            }
        } else if (radix < 2 || radix > 36) {
            return JSNumber.NAN
        } else if (radix == 16) {
            stripHexPrefix = true
        }

        if (stripHexPrefix && text.length >= 2 && text[0] == '0' && (text[1] == 'x' || text[1] == 'X')) {
            text = text.substring(2)
        }

        var value = 0.0
        var hasDigits = false
        for (c in text) {
            val digit = digitValue(c)
            if (digit < 0 || digit >= radix) break
            value = value * radix + digit
            hasDigits = true
        }

        return if (hasDigits) JSNumber(sign * value) else JSNumber.NAN
    }

    /**
     * parseFloat(string)。
     *
     * 与 Number() 不同，parseFloat 会解析字符串前缀中的合法数字，
     * 例如 "12px" -> 12。
     */
    suspend fun parseFloat(interpreter: Interpreter, args: List<JSValue>): JSValue {
        val input = interpreter.toStringValue(args.getOrElse(0) { com.muedsa.js.runtime.value.JSUndefined })
            .trimJsWhitespace()

        if (input.startsWith("Infinity") || input.startsWith("+Infinity")) {
            return JSNumber(Double.POSITIVE_INFINITY)
        }
        if (input.startsWith("-Infinity")) {
            return JSNumber(Double.NEGATIVE_INFINITY)
        }

        var index = 0
        val length = input.length
        if (index < length && (input[index] == '+' || input[index] == '-')) index++

        var digitsStart = index
        while (index < length && input[index].isDigit()) index++

        var hasDigits = index > digitsStart

        if (index < length && input[index] == '.') {
            index++
            val fractionStart = index
            while (index < length && input[index].isDigit()) index++
            if (index > fractionStart) hasDigits = true
        }

        if (!hasDigits) return JSNumber.NAN

        if (index < length && (input[index] == 'e' || input[index] == 'E')) {
            val exponentStart = index
            index++
            if (index < length && (input[index] == '+' || input[index] == '-')) index++
            val exponentDigitsStart = index
            while (index < length && input[index].isDigit()) index++
            if (index == exponentDigitsStart) {
                // 指数部分不完整：回退到指数之前
                index = exponentStart
            }
        }

        val numericText = input.substring(0, index)
        return JSNumber(numericText.toDoubleOrNull() ?: Double.NaN)
    }

    private fun digitValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'z' -> c - 'a' + 10
        in 'A'..'Z' -> c - 'A' + 10
        else -> -1
    }
}
