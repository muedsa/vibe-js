package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.math.truncate
import kotlin.random.Random

/**
 * Math 对象。
 */
object MathBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val math = intrinsics.mathObject

        fun constant(name: String, value: Double) {
            math.defineDataProperty(
                key = PropertyKey.Str(name),
                value = JSNumber(value),
                writable = false,
                enumerable = false,
                configurable = false,
            )
        }

        constant("E", E)
        constant("LN2", ln(2.0))
        constant("LN10", ln(10.0))
        constant("LOG2E", log2(E))
        constant("LOG10E", log10(E))
        constant("PI", PI)
        constant("SQRT1_2", sqrt(0.5))
        constant("SQRT2", sqrt(2.0))

        math.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = com.muedsa.js.runtime.value.JSString("Math"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        fun unary(name: String, operation: (Double) -> Double) {
            math.defineDataProperty(
                key = PropertyKey.Str(name),
                value = BuiltinHelpers.method(intrinsics, name, 1) { interpreter, _, args ->
                    JSNumber(operation(interpreter.toNumber(args.getOrElse(0) { JSUndefined })))
                },
            )
        }

        fun binary(name: String, operation: (Double, Double) -> Double) {
            math.defineDataProperty(
                key = PropertyKey.Str(name),
                value = BuiltinHelpers.method(intrinsics, name, 2) { interpreter, _, args ->
                    JSNumber(
                        operation(
                            interpreter.toNumber(args.getOrElse(0) { JSUndefined }),
                            interpreter.toNumber(args.getOrElse(1) { JSUndefined }),
                        )
                    )
                },
            )
        }

        unary("abs") { value -> abs(value) }
        unary("acos") { value -> acos(value) }
        unary("acosh") { value -> acosh(value) }
        unary("asin") { value -> asin(value) }
        unary("asinh") { value -> asinh(value) }
        unary("atan") { value -> atan(value) }
        unary("atanh") { value -> atanh(value) }
        unary("cbrt") { value -> if (value < 0) -(-value).pow(1.0 / 3.0) else value.pow(1.0 / 3.0) }
        unary("ceil") { value -> kotlin.math.ceil(value) }
        unary("cos") { value -> kotlin.math.cos(value) }
        unary("cosh") { value -> cosh(value) }
        unary("exp") { value -> exp(value) }
        unary("expm1") { value -> expm1(value) }
        unary("floor") { value -> floor(value) }
        unary("fround") { value -> value.toFloat().toDouble() }
        unary("log") { value -> ln(value) }
        unary("log1p") { value -> ln1p(value) }
        unary("log10") { value -> log10(value) }
        unary("log2") { value -> log2(value) }
        unary("round") { value -> mathRound(value) }
        unary("sign") { value -> sign(value) }
        unary("sin") { value -> sin(value) }
        unary("sinh") { value -> sinh(value) }
        unary("sqrt") { value -> sqrt(value) }
        unary("tan") { value -> tan(value) }
        unary("tanh") { value -> tanh(value) }
        unary("trunc") { value -> truncate(value) }

        binary("atan2") { y, x -> atan2(y, x) }
        binary("pow") { base, exponent -> base.pow(exponent) }

        math.defineDataProperty(
            key = PropertyKey.Str("hypot"),
            value = BuiltinHelpers.method(intrinsics, "hypot", 2) { interpreter, _, args ->
                var hasNaN = false
                var infinite = false
                var sum = 0.0
                for (argument in args) {
                    val value = interpreter.toNumber(argument)
                    if (value.isInfinite()) {
                        infinite = true
                        break
                    }
                    if (value.isNaN()) {
                        hasNaN = true
                        break
                    }
                    sum += value * value
                }
                when {
                    infinite -> JSNumber(Double.POSITIVE_INFINITY)
                    hasNaN -> JSNumber.NAN
                    else -> JSNumber(sqrt(sum))
                }
            },
        )

        math.defineDataProperty(
            key = PropertyKey.Str("imul"),
            value = BuiltinHelpers.method(intrinsics, "imul", 2) { interpreter, _, args ->
                val left = interpreter.toUint32(args.getOrElse(0) { JSUndefined }).toInt()
                val right = interpreter.toUint32(args.getOrElse(1) { JSUndefined }).toInt()
                JSNumber((left * right).toDouble())
            },
        )

        math.defineDataProperty(
            key = PropertyKey.Str("clz32"),
            value = BuiltinHelpers.method(intrinsics, "clz32", 1) { interpreter, _, args ->
                val value = interpreter.toUint32(args.getOrElse(0) { JSUndefined }).toInt()
                JSNumber(value.countLeadingZeroBits().toDouble())
            },
        )

        math.defineDataProperty(
            key = PropertyKey.Str("max"),
            value = BuiltinHelpers.method(intrinsics, "max", 2) { interpreter, _, args ->
                var result = Double.NEGATIVE_INFINITY
                var hasNaN = false
                for (argument in args) {
                    val value = interpreter.toNumber(argument)
                    if (value.isNaN()) {
                        hasNaN = true
                        break
                    }
                    result = max(result, value)
                }
                if (hasNaN) JSNumber.NAN else JSNumber(result)
            },
        )

        math.defineDataProperty(
            key = PropertyKey.Str("min"),
            value = BuiltinHelpers.method(intrinsics, "min", 2) { interpreter, _, args ->
                var result = Double.POSITIVE_INFINITY
                var hasNaN = false
                for (argument in args) {
                    val value = interpreter.toNumber(argument)
                    if (value.isNaN()) {
                        hasNaN = true
                        break
                    }
                    result = min(result, value)
                }
                if (hasNaN) JSNumber.NAN else JSNumber(result)
            },
        )

        math.defineDataProperty(
            key = PropertyKey.Str("sumPrecise"),
            value = BuiltinHelpers.method(intrinsics, "sumPrecise", 1) { interpreter, _, args ->
                val iterable = args.getOrElse(0) { JSUndefined }
                if (iterable is JSNull || iterable is JSUndefined) {
                    interpreter.typeError("Math.sumPrecise requires an iterable")
                }
                val exact = ExactSum()
                var hasNaN = false
                var positiveInfinity = false
                var negativeInfinity = false
                var allNegativeZero = true
                interpreter.consumeIterator(iterable) { value ->
                    if (value !is JSNumber) {
                        interpreter.typeError("Math.sumPrecise elements must be numbers")
                    }
                    val number = value.value
                    when {
                        number.isNaN() -> hasNaN = true
                        number == Double.POSITIVE_INFINITY -> positiveInfinity = true
                        number == Double.NEGATIVE_INFINITY -> negativeInfinity = true
                        else -> {
                            val isNegativeZero = number == 0.0 && 1.0 / number < 0.0
                            if (!isNegativeZero) allNegativeZero = false
                            if (number != 0.0) exact.add(number)
                        }
                    }
                }
                when {
                    hasNaN || (positiveInfinity && negativeInfinity) -> JSNumber(Double.NaN)
                    positiveInfinity -> JSNumber(Double.POSITIVE_INFINITY)
                    negativeInfinity -> JSNumber(Double.NEGATIVE_INFINITY)
                    exact.isZero() -> JSNumber(if (allNegativeZero) -0.0 else 0.0)
                    else -> JSNumber(exact.toDouble())
                }
            },
        )

        math.defineDataProperty(
            key = PropertyKey.Str("random"),
            value = BuiltinHelpers.method(intrinsics, "random", 0) { _, _, _ ->
                JSNumber(Random.nextDouble())
            },
        )
    }
    /** Math.round：-0.5 <= x < 0 返回 -0，0 <= x < 0.5 返回 +0。 */
    private fun mathRound(value: Double): Double {
        if (value.isNaN() || value.isInfinite() || value == 0.0) return value
        // |x| 超过 2^52 时 x + 0.5 会因精度丢失而改变结果
        if (kotlin.math.abs(value) >= 4503599627370496.0) return value
        if (value > 0.0 && value < 0.5) return 0.0
        if (value >= -0.5 && value < 0.0) return -0.0
        return floor(value + 0.5)
    }

    /**
     * Math.sumPrecise 的精确累加器。
     *
     * 以 2^-1074 为最小单位，用符号 + 64 位字长整数保存所有有限 double 的精确和，
     * 最后按 roundTiesToEven 舍入成 double；可正确处理中间溢出后相互抵消的场景。
     */
    private class ExactSum {
        /** 小端序幅值字（word 0 为最低 64 位）。 */
        private val words = ArrayList<ULong>()
        private var negative = false

        fun isZero(): Boolean = words.isEmpty()

        fun add(value: Double) {
            val bits = value.toRawBits()
            val exponentBits = ((bits ushr 52) and 0x7FF).toInt()
            var mantissa = bits and 0x000FFFFFFFFFFFFFL
            val shift: Int
            if (exponentBits == 0) {
                if (mantissa == 0L) return
                shift = -1074
            } else {
                mantissa = mantissa or (1L shl 52)
                shift = exponentBits - 1075
            }
            addShifted(mantissa, shift + 1074, value < 0.0)
        }

        /** 把带符号的 mantissa * 2^(bitPos-1074) 累加进当前幅值。 */
        private fun addShifted(mantissa: Long, bitPos: Int, subtract: Boolean) {
            val wordIndex = bitPos / 64
            val offset = bitPos % 64
            val shifted = mantissa.toULong()
            val temp = ULongArray(wordIndex + 3)
            temp[wordIndex] = shifted shl offset
            temp[wordIndex + 1] = if (offset == 0) 0uL else shifted shr (64 - offset)
            if (negative == subtract) {
                addMagnitude(temp)
            } else {
                when (val comparison = compareMagnitude(temp)) {
                    0 -> {
                        words.clear()
                        negative = false
                    }

                    1 -> subtractMagnitude(temp)
                    else -> {
                        val current = words.toULongArray()
                        words.clear()
                        for (word in temp) words.add(word)
                        trim()
                        subtractMagnitude(current)
                        negative = subtract
                    }
                }
            }
        }

        private fun addMagnitude(other: ULongArray) {
            var carry = 0uL
            val size = max(words.size, other.size)
            for (index in 0 until size) {
                val left = if (index < words.size) words[index] else 0uL
                val right = if (index < other.size) other[index] else 0uL
                val sum = left + right
                val carry1 = if (sum < left) 1uL else 0uL
                val withCarry = sum + carry
                val carry2 = if (withCarry < sum) 1uL else 0uL
                if (index < words.size) words[index] = withCarry else words.add(withCarry)
                carry = carry1 or carry2
            }
            if (carry != 0uL) words.add(carry)
            trim()
        }

        private fun subtractMagnitude(other: ULongArray) {
            var borrow = 0uL
            for (index in 0 until words.size) {
                val left = words[index]
                val right = if (index < other.size) other[index] else 0uL
                val difference = left - right
                val borrow1 = if (left < right) 1uL else 0uL
                val withBorrow = difference - borrow
                val borrow2 = if (difference < borrow) 1uL else 0uL
                words[index] = withBorrow
                borrow = borrow1 or borrow2
            }
            trim()
        }

        /** 比较幅值：1 表示当前更大，-1 表示更小，0 相等。 */
        private fun compareMagnitude(other: ULongArray): Int {
            val size = max(words.size, other.size)
            for (index in size - 1 downTo 0) {
                val left = if (index < words.size) words[index] else 0uL
                val right = if (index < other.size) other[index] else 0uL
                if (left != right) return if (left > right) 1 else -1
            }
            return 0
        }

        private fun trim() {
            while (words.isNotEmpty() && words.last() == 0uL) {
                words.removeAt(words.size - 1)
            }
        }

        /** 幅值舍入为 double（roundTiesToEven）。 */
        fun toDouble(): Double {
            trim()
            if (words.isEmpty()) return 0.0
            val topWord = words.last()
            val topBit = words.size * 64 - topWord.countLeadingZeroBits() - 1
            val shift = max(0, topBit - 52)
            var high = extractBits(shift, 53)
            if (shift > 0) {
                val roundBit = bitAt(shift - 1)
                val sticky = anyBitBelow(shift - 2)
                if (roundBit && (sticky || (high and 1L) != 0L)) {
                    high++
                }
            }
            var exponent = shift - 1074
            if (high == (1L shl 53)) {
                high = high shr 1
                exponent++
            }
            val magnitude = high.toDouble() * powerOfTwo(exponent)
            return if (negative) -magnitude else magnitude
        }

        /** 2^n 的精确构造（n 超出范围时返回 0 或 Infinity）。 */
        private fun powerOfTwo(exponent: Int): Double {
            if (exponent > 1023) return Double.POSITIVE_INFINITY
            if (exponent >= -1022) {
                return Double.fromBits((exponent + 1023).toLong() shl 52)
            }
            if (exponent >= -1074) {
                return Double.fromBits(1L shl (exponent + 1074))
            }
            return 0.0
        }

        private fun bitAt(position: Int): Boolean {
            val index = position / 64
            if (index >= words.size) return false
            return ((words[index] shr (position % 64)) and 1uL) != 0uL
        }

        private fun extractBits(start: Int, count: Int): Long {
            var result = 0L
            for (index in 0 until count) {
                if (bitAt(start + index)) result = result or (1L shl index)
            }
            return result
        }

        /** 是否存在低于等于 limit 位的置位。 */
        private fun anyBitBelow(limit: Int): Boolean {
            if (limit < 0) return false
            val fullWords = limit / 64
            for (index in 0 until min(fullWords + 1, words.size)) {
                val word = words[index]
                if (index < fullWords) {
                    if (word != 0uL) return true
                } else {
                    val bits = limit % 64 + 1
                    val mask = if (bits == 64) ULong.MAX_VALUE else (1uL shl bits) - 1uL
                    if ((word and mask) != 0uL) return true
                }
            }
            return false
        }
    }

}
