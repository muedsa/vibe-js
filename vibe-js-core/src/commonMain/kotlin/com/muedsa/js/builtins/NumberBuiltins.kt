package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSBoxedPrimitive
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.NumberFormatting
import com.muedsa.js.runtime.value.NumberRadixFormatting
import com.muedsa.js.runtime.value.PropertyKey
import kotlin.math.abs

/**
 * Number 构造器与 Number.prototype。
 */
object NumberBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val numberPrototype = intrinsics.numberPrototype

        val numberConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "Number",
            length = 1,
            prototypeObject = numberPrototype,
        ) { interpreter, _, args ->
            if (args.isEmpty()) JSNumber.ZERO else JSNumber(interpreter.toNumber(args[0]))
        }

        numberPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = numberConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )

        installConstants(numberConstructor)
        installPrototypeMethods(registry)
        installStaticMethods(registry, numberConstructor)

        registry.defineGlobal("Number", numberConstructor)
    }

    private fun installConstants(numberConstructor: com.muedsa.js.runtime.value.JSNativeFunction) {
        fun constant(name: String, value: Double) {
            numberConstructor.defineDataProperty(
                key = PropertyKey.Str(name),
                value = JSNumber(value),
                writable = false,
                enumerable = false,
                configurable = false,
            )
        }
        constant("MAX_VALUE", Double.MAX_VALUE)
        constant("MIN_VALUE", Double.MIN_VALUE)
        constant("POSITIVE_INFINITY", Double.POSITIVE_INFINITY)
        constant("NEGATIVE_INFINITY", Double.NEGATIVE_INFINITY)
        constant("NaN", Double.NaN)
        constant("MAX_SAFE_INTEGER", 9007199254740991.0)
        constant("MIN_SAFE_INTEGER", -9007199254740991.0)
        constant("EPSILON", 2.220446049250313e-16)
    }

    /** Number.prototype 方法要求 this 为 Number 原始值/包装对象。 */
    private fun numberReceiver(
        interpreter: Interpreter,
        thisValue: JSValue,
        name: String,
    ): Double = when (thisValue) {
        is JSNumber -> thisValue.value
        is JSBoxedPrimitive -> (thisValue.primitive as? JSNumber)?.value
            ?: interpreter.typeError("$name requires that 'this' be a Number")

        interpreter.intrinsics.numberPrototype -> 0.0
        else -> interpreter.typeError("$name requires that 'this' be a Number")
    }

    private fun installPrototypeMethods(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val numberPrototype = intrinsics.numberPrototype

        numberPrototype.defineDataProperty(
            key = PropertyKey.Str("valueOf"),
            value = BuiltinHelpers.method(intrinsics, "valueOf", 0) { interpreter, thisValue, _ ->
                JSNumber(numberReceiver(interpreter, thisValue, "Number.prototype.valueOf"))
            },
        )


        numberPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            value = BuiltinHelpers.method(intrinsics, "toString", 1) { interpreter, thisValue, args ->
                val number = numberReceiver(interpreter, thisValue, "Number.prototype method")
                val radix = args.getOrNull(0)
                    ?.takeUnless { it is JSUndefined }
                    ?.let { interpreter.toNumber(it).toInt() } ?: 10
                if (radix < 2 || radix > 36) {
                    interpreter.rangeError("toString() radix must be between 2 and 36")
                }
                JSString(numberToString(number, radix))
            },
        )

        numberPrototype.defineDataProperty(
            key = PropertyKey.Str("toFixed"),
            value = BuiltinHelpers.method(intrinsics, "toFixed", 1) { interpreter, thisValue, args ->
                val number = numberReceiver(interpreter, thisValue, "Number.prototype method")
                val digits = args.getOrNull(0)
                    ?.takeUnless { it is JSUndefined }
                    ?.let { interpreter.toNumber(it).toInt() } ?: 0
                if (digits < 0 || digits > 100) {
                    interpreter.rangeError("toFixed() digits argument must be between 0 and 100")
                }
                JSString(toFixed(number, digits))
            },
        )

        numberPrototype.defineDataProperty(
            key = PropertyKey.Str("toExponential"),
            value = BuiltinHelpers.method(intrinsics, "toExponential", 1) { interpreter, thisValue, args ->
                val number = numberReceiver(interpreter, thisValue, "Number.prototype method")
                val digits = args.getOrNull(0)
                    ?.takeUnless { it is JSUndefined }
                    ?.let { interpreter.toNumber(it).toInt() }
                // 规范顺序：非有限值在范围检查之前返回 ToString(x)
                if (number.isNaN() || number.isInfinite()) {
                    return@method JSString(NumberFormatting.toString(number))
                }
                if (digits != null && (digits < 0 || digits > 100)) {
                    interpreter.rangeError("toExponential() argument must be between 0 and 100")
                }
                JSString(toExponential(number, digits))
            },
        )

        numberPrototype.defineDataProperty(
            key = PropertyKey.Str("toPrecision"),
            value = BuiltinHelpers.method(intrinsics, "toPrecision", 1) { interpreter, thisValue, args ->
                val number = numberReceiver(interpreter, thisValue, "Number.prototype method")
                val precision = args.getOrNull(0)
                    ?.takeUnless { it is JSUndefined }
                    ?.let { interpreter.toNumber(it).toInt() }
                when {
                    precision == null -> JSString(NumberFormatting.toString(number))
                    // 规范顺序：非有限值在范围检查之前返回 ToString(x)
                    number.isNaN() || number.isInfinite() ->
                        JSString(NumberFormatting.toString(number))

                    precision < 1 || precision > 100 ->
                        interpreter.rangeError("toPrecision() argument must be between 1 and 100")

                    else -> JSString(toPrecision(number, precision))
                }
            },
        )

        numberPrototype.defineDataProperty(
            key = PropertyKey.Str("toLocaleString"),
            value = BuiltinHelpers.method(intrinsics, "toLocaleString", 0) { interpreter, thisValue, _ ->
                JSString(NumberFormatting.toString(numberReceiver(interpreter, thisValue, "Number.prototype.toLocaleString")))
            },
        )
    }

    private fun installStaticMethods(registry: BuiltinRegistry, numberConstructor: com.muedsa.js.runtime.value.JSNativeFunction) {
        val intrinsics = registry.intrinsics

        numberConstructor.defineDataProperty(
            key = PropertyKey.Str("isNaN"),
            value = BuiltinHelpers.method(intrinsics, "isNaN", 1) { _, _, args ->
                val value = args.getOrNull(0)
                JSBoolean.getJsBoolean(value is JSNumber && value.value.isNaN())
            },
        )

        numberConstructor.defineDataProperty(
            key = PropertyKey.Str("isFinite"),
            value = BuiltinHelpers.method(intrinsics, "isFinite", 1) { _, _, args ->
                val value = args.getOrNull(0)
                JSBoolean.getJsBoolean(value is JSNumber && value.value.isFinite())
            },
        )

        numberConstructor.defineDataProperty(
            key = PropertyKey.Str("isInteger"),
            value = BuiltinHelpers.method(intrinsics, "isInteger", 1) { _, _, args ->
                val value = args.getOrNull(0)
                JSBoolean.getJsBoolean(
                    value is JSNumber && value.value.isFinite() && value.value % 1.0 == 0.0
                )
            },
        )

        numberConstructor.defineDataProperty(
            key = PropertyKey.Str("isSafeInteger"),
            value = BuiltinHelpers.method(intrinsics, "isSafeInteger", 1) { _, _, args ->
                val value = args.getOrNull(0)
                JSBoolean.getJsBoolean(
                    value is JSNumber && value.value.isFinite() &&
                        value.value % 1.0 == 0.0 &&
                        kotlin.math.abs(value.value) <= 9007199254740991.0
                )
            },
        )

        val parseIntFunction = BuiltinHelpers.method(intrinsics, "parseInt", 2) { interpreter, _, args ->
            NumberParsing.parseInt(interpreter, args)
        }
        intrinsics.parseIntFunction = parseIntFunction
        numberConstructor.defineDataProperty(
            key = PropertyKey.Str("parseInt"),
            value = parseIntFunction,
        )

        val parseFloatFunction = BuiltinHelpers.method(intrinsics, "parseFloat", 1) { interpreter, _, args ->
            NumberParsing.parseFloat(interpreter, args)
        }
        intrinsics.parseFloatFunction = parseFloatFunction
        numberConstructor.defineDataProperty(
            key = PropertyKey.Str("parseFloat"),
            value = parseFloatFunction,
        )
    }

    /**
     * 数字转指定进制字符串（2-36 进制）。
     *
     * 10 进制使用 NumberFormatting 的规范格式化；其余进制使用精确二进制
     * 大整数展开并按最短往返规则选择有效数字（见 NumberRadixFormatting）。
     */
    private fun numberToString(number: Double, radix: Int): String {
        if (radix == 10) return NumberFormatting.toString(number)
        if (number.isNaN()) return "NaN"
        if (number == Double.POSITIVE_INFINITY) return "Infinity"
        if (number == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (number == 0.0) return "0"

        val negative = number < 0
        val text = NumberRadixFormatting.toString(abs(number), radix)
        return if (negative) "-$text" else text
    }

    /**
     * toFixed 的精确实现。
     *
     * 按规范取使 n / 10^digits 最接近 |x| 的整数 n（并列时取更大的 n），
     * 再按固定小数位输出；负值即使舍入为 0 也保留负号（如 (-0.0001).toFixed(2)）。
     */
    private fun toFixed(number: Double, digits: Int): String {
        if (!number.isFinite() || abs(number) >= 1e21) {
            return NumberFormatting.toString(number)
        }
        // ±0 输出正零格式
        if (number == 0.0) {
            return if (digits == 0) "0" else "0." + "0".repeat(digits)
        }
        val negative = number < 0
        val decimal = exactDecimal(abs(number))
        val shift = decimal.decimalExponent + digits
        val scaled = if (shift >= 0) {
            decimal.magnitude.also { it.timesPowerOfTen(shift) }
        } else {
            decimal.magnitude.dropDigitsWithHalfUp(-shift)
        }
        var text = scaled.toString()
        if (digits > 0) {
            // 补足至少 1 位整数与 digits 位小数
            if (text.length <= digits) text = text.padStart(digits + 1, '0')
            text = text.substring(0, text.length - digits) + "." + text.substring(text.length - digits)
        }
        return (if (negative) "-" else "") + text
    }

    /**
     * 轻量十进制大整数（基 10^6，小端存储）。
     *
     * 仅用于 Double 的精确十进制展开：乘以 2/5 的幂、十进制左移、
     * 按位舍入以及十进制字符串输出；不追求通用大数能力。
     */
    private class DecimalBigInt private constructor(private val limbs: MutableList<Int>) {

        companion object {
            private const val BASE = 1_000_000

            fun fromLong(value: Long): DecimalBigInt {
                var current = value
                val digits = mutableListOf<Int>()
                while (current > 0L) {
                    digits.add((current % BASE).toInt())
                    current /= BASE
                }
                if (digits.isEmpty()) digits.add(0)
                return DecimalBigInt(digits)
            }

            /** 由纯数字字符串构造（每 6 位一组，从低位开始解析）。 */
            fun fromDecimalString(text: String): DecimalBigInt {
                val digits = mutableListOf<Int>()
                var end = text.length
                while (end > 0) {
                    val start = (end - 6).coerceAtLeast(0)
                    digits.add(text.substring(start, end).toInt())
                    end = start
                }
                if (digits.isEmpty()) digits.add(0)
                return DecimalBigInt(digits)
            }
        }

        fun multiplySmall(factor: Int) {
            var carry = 0L
            for (index in limbs.indices) {
                val product = limbs[index].toLong() * factor + carry
                limbs[index] = (product % BASE).toInt()
                carry = product / BASE
            }
            while (carry > 0L) {
                limbs.add((carry % BASE).toInt())
                carry /= BASE
            }
        }

        fun increment() {
            var index = 0
            while (index < limbs.size) {
                val value = limbs[index] + 1
                if (value < BASE) {
                    limbs[index] = value
                    return
                }
                limbs[index] = 0
                index++
            }
            limbs.add(1)
        }

        /** 乘以 10 的 power 次（十进制左移）。 */
        fun timesPowerOfTen(power: Int) {
            repeat(power) { multiplySmall(10) }
        }

        /**
         * 删除最低的 dropCount 个十进制位并按“四舍五入”取整
         * （规范在恰好半值时选择更大的 n）。
         */
        fun dropDigitsWithHalfUp(dropCount: Int): DecimalBigInt {
            if (dropCount <= 0) return this
            val text = toString()
            if (dropCount > text.length) return fromLong(0L)
            val cut = text.length - dropCount
            // 全部位数被丢弃（如 0.9999 保留 0 位小数）：按最高位判断是否进位到 1
            if (cut == 0) {
                return if (text[0] >= '5') fromLong(1L) else fromLong(0L)
            }
            val result = fromDecimalString(text.substring(0, cut))
            if (text[cut] >= '5') result.increment()
            return result
        }

        override fun toString(): String {
            val builder = StringBuilder(limbs.last().toString())
            for (index in limbs.lastIndex - 1 downTo 0) {
                builder.append(limbs[index].toString().padStart(6, '0'))
            }
            return builder.toString()
        }
    }

    /** 一个正有限 Double 的精确十进制表示：value = digits × 10^decimalExponent。 */
    private class ExactDecimal(
        val magnitude: DecimalBigInt,
        val digits: String,
        val decimalExponent: Int,
    ) {
        /** 最高位数字的十进制指数，即精确的 floor(log10(value))。 */
        val leadingExponent: Int
            get() = digits.length - 1 + decimalExponent
    }

    /**
     * 把正有限 Double 精确展开为十进制大整数。
     *
     * 依据 IEEE 754 分解 x = m × 2^e：e ≥ 0 时 x = (m × 2^e) × 10^0；
     * e < 0 时 x = (m × 5^(-e)) × 10^e，从而完整保留所有有效数字。
     */
    private fun exactDecimal(value: Double): ExactDecimal {
        val bits = value.toRawBits()
        val exponentBits = ((bits ushr 52) and 0x7FFL).toInt()
        val fractionBits = bits and ((1L shl 52) - 1L)
        val mantissa: Long
        val binaryExponent: Int
        if (exponentBits == 0) {
            // 次正规数没有隐含首位 1，指数固定为 -1074
            mantissa = fractionBits
            binaryExponent = -1074
        } else {
            mantissa = fractionBits or (1L shl 52)
            binaryExponent = exponentBits - 1075
        }
        val magnitude = DecimalBigInt.fromLong(mantissa)
        val decimalExponent: Int
        if (binaryExponent >= 0) {
            repeat(binaryExponent) { magnitude.multiplySmall(2) }
            decimalExponent = 0
        } else {
            // m × 2^e = m × 5^(-e) × 10^e
            repeat(-binaryExponent) { magnitude.multiplySmall(5) }
            decimalExponent = binaryExponent
        }
        return ExactDecimal(magnitude, magnitude.toString(), decimalExponent)
    }

    /**
     * 把精确十进制值舍入到 kept 个有效数字（规范在并列时取更大的 n）。
     *
     * 返回数字串；若高位全为 9 导致进位（如 999… → 1000…），长度会多一位。
     */
    private fun roundSignificant(decimal: ExactDecimal, kept: Int): String {
        val dropped = decimal.digits.length - kept
        val rounded = if (dropped > 0) {
            decimal.magnitude.dropDigitsWithHalfUp(dropped)
        } else {
            decimal.magnitude.also { it.timesPowerOfTen(-dropped) }
        }
        return rounded.toString()
    }

    /** 指数形式的后缀：`e+E` / `e-E`（E 为绝对值）。 */
    private fun exponentSuffix(exponent: Int): String =
        (if (exponent >= 0) "+" else "-") + abs(exponent)

    /**
     * 规范要求的精确十进制展开（fractionDigits 已定义时的 toExponential）。
     *
     * 返回 `d.ddd...e±E` 形式的无符号文本，舍入并列时取更大的 n。
     */
    private fun exactToExponential(value: Double, fractionDigits: Int): String {
        val decimal = exactDecimal(value)
        var exponent = decimal.leadingExponent
        val keptDigits = fractionDigits + 1
        var digits = roundSignificant(decimal, keptDigits)
        if (digits.length > keptDigits) {
            // 高位全为 9 时进位会多出一位（如 9.99 → 10.0），提升指数
            digits = digits.substring(0, keptDigits)
            exponent++
        }
        val mantissaText = if (fractionDigits == 0) {
            digits
        } else {
            digits[0] + "." + digits.substring(1)
        }
        return mantissaText + "e" + exponentSuffix(exponent)
    }

    /**
     * 规范要求的 toPrecision 精确实现（precision 已定义且为非零有限值）。
     *
     * 先按 precision 位有效数字舍入；若进位使指数提升，则按提升后的指数
     * 判断指数/定点形式（例如 1e-6 的双精度值在 16 位有效数字时进位到 e=-6）。
     */
    private fun exactToPrecision(value: Double, precision: Int): String {
        val decimal = exactDecimal(value)
        var exponent = decimal.leadingExponent
        var digits = roundSignificant(decimal, precision)
        if (digits.length > precision) {
            digits = digits.substring(0, precision)
            exponent++
        }
        if (exponent < -6 || exponent >= precision) {
            val mantissaText = if (precision == 1) digits else digits[0] + "." + digits.substring(1)
            return mantissaText + "e" + exponentSuffix(exponent)
        }
        return if (exponent >= 0) {
            val integerDigits = exponent + 1
            if (integerDigits == digits.length) {
                digits
            } else {
                digits.substring(0, integerDigits) + "." + digits.substring(integerDigits)
            }
        } else {
            "0." + "0".repeat(-exponent - 1) + digits
        }
    }

    /**
     * 未提供 fractionDigits 的 toExponential：使用 Number::toString 的最短往返
     * 十进制表示，再改写为指数形式（n 的位数尽可能少）。
     */
    private fun shortestExponentialText(value: Double): String {
        val text = NumberFormatting.toString(value)
        if (text.contains('e')) return text
        val integerPart = text.substringBefore('.')
        val fractionPart = text.substringAfter('.', "")
        val allDigits = integerPart + fractionPart
        val firstNonZero = allDigits.indexOfFirst { it != '0' }
        // 非零有限值的数字串必然包含非零位
        val exponent = integerPart.length - firstNonZero - 1
        val significant = allDigits.substring(firstNonZero).trimEnd('0')
        val mantissa = significant[0] +
            (if (significant.length > 1) "." + significant.substring(1) else "")
        return mantissa + "e" + exponentSuffix(exponent)
    }

    /**
     * toExponential。
     *
     * @param digits 小数位数；null 表示使用最短往返表示
     */
    private fun toExponential(number: Double, digits: Int?): String {
        if (!number.isFinite()) return NumberFormatting.toString(number)
        if (number == 0.0) {
            val decimals = digits ?: 0
            val fraction = if (decimals > 0) "." + "0".repeat(decimals) else ""
            return "0${fraction}e+0"
        }

        val negative = number < 0
        val value = abs(number)
        val text = if (digits != null) {
            exactToExponential(value, digits)
        } else {
            shortestExponentialText(value)
        }
        return (if (negative) "-" else "") + text
    }

    /**
     * toPrecision。
     */
    private fun toPrecision(number: Double, precision: Int): String {
        if (!number.isFinite()) return NumberFormatting.toString(number)
        // 规范：±0 输出为正零格式（不含负号），p=1 时为 "0"
        if (number == 0.0) {
            return if (precision == 1) "0" else "0." + "0".repeat(precision - 1)
        }
        val sign = if (number < 0) "-" else ""
        return sign + exactToPrecision(abs(number), precision)
    }
}
