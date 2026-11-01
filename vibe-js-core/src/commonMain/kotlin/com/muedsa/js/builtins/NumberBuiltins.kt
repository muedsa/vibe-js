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
import com.muedsa.js.runtime.value.PropertyKey
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

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
     * 数字转指定进制字符串（支持 2-36 进制）。
     */
    private fun numberToString(number: Double, radix: Int): String {
        if (radix == 10) return NumberFormatting.toString(number)
        if (number.isNaN()) return "NaN"
        if (number == Double.POSITIVE_INFINITY) return "Infinity"
        if (number == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (number == 0.0) return "0"

        val negative = number < 0
        val value = abs(number)
        val integerPart = floor(value).toLong()
        var integerText = if (integerPart == 0L) "0" else integerPart.toString(radix)

        var fraction = value - floor(value)
        val fractionBuilder = StringBuilder()
        var iteration = 0
        while (fraction > 0.0 && iteration < 20) {
            fraction *= radix
            val digit = floor(fraction).toInt()
            fractionBuilder.append(digitChar(digit))
            fraction -= digit
            iteration++
        }
        val fractionText = fractionBuilder.toString().trimEnd('0')

        val text = if (fractionText.isEmpty()) integerText else "$integerText.$fractionText"
        return if (negative) "-$text" else text
    }

    private fun digitChar(digit: Int): Char =
        if (digit < 10) ('0' + digit) else ('a' + (digit - 10))

    /**
     * toFixed 的简化实现。
     */
    private fun toFixed(number: Double, digits: Int): String {
        if (!number.isFinite() || abs(number) >= 1e21) {
            return NumberFormatting.toString(number)
        }
        val negative = number < 0
        val value = abs(number)
        var factor = 1.0
        repeat(digits) { factor *= 10.0 }
        val scaled = floor(value * factor + 0.5)
        val integerPart = floor(scaled / factor).toLong()
        val fractionPart = (scaled - integerPart * factor).toLong().toDouble()
        val text = if (digits == 0) {
            integerPart.toString()
        } else {
            "${integerPart}.${fractionPart.toLong().toString().padStart(digits, '0')}"
        }
        return if (negative && (integerPart != 0L || fractionPart != 0.0)) "-$text" else text
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
            if (dropCount >= text.length) return fromLong(0L)
            val cut = text.length - dropCount
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

    /**
     * 规范要求的精确十进制展开（fractionDigits 已定义时的 toExponential）。
     *
     * 将 Double 分解为 m × 2^e 后改写成十进制大整数：
     * - e ≥ 0 时 x = (m × 2^e) × 10^0；
     * - e < 0 时 x = (m × 5^(-e)) × 10^e。
     * 这样 x 的完整十进制数字串可以精确保存，再按保留位数舍入；
     * 规范要求恰为半值时取更大的 n，即对绝对值执行“逢五进一”。
     *
     * @return `d.ddd...e±E` 形式的无符号文本；非有限值或参数越界时返回 null
     */
    private fun exactToExponential(value: Double, fractionDigits: Int): String? {
        val bits = value.toRawBits()
        val exponentBits = ((bits ushr 52) and 0x7FFL).toInt()
        if (exponentBits == 0x7FF) return null
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
        if (mantissa == 0L) return null

        // 精确十进制表示：x = magnitude × 10^decimalExponent
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

        val digitsText = magnitude.toString()
        // 最高位数字对应的十进制指数；由数字串长度精确得出，避免 log10 误差
        var exponent = digitsText.length - 1 + decimalExponent
        val keptDigits = fractionDigits + 1
        // 保留 keptDigits 位：多余低位四舍五入，不足则补零
        val dropped = digitsText.length - keptDigits
        var rounded = if (dropped > 0) {
            magnitude.dropDigitsWithHalfUp(dropped)
        } else {
            magnitude.also { it.timesPowerOfTen(-dropped) }
        }
        var roundedText = rounded.toString()
        // 高位全为 9 时舍入进位会多出一位（如 9.99 → 10.0），提升指数
        if (roundedText.length > keptDigits) {
            rounded = rounded.dropDigitsWithHalfUp(roundedText.length - keptDigits)
            roundedText = rounded.toString()
            exponent++
        }
        val mantissaText = if (fractionDigits == 0) {
            roundedText
        } else {
            roundedText.substring(0, 1) + "." + roundedText.substring(1)
        }
        val exponentSign = if (exponent >= 0) "+" else "-"
        return mantissaText + "e" + exponentSign + abs(exponent)
    }

    /**
     * toExponential 的简化实现。
     *
     * @param digits 小数位数；null 表示使用最短表示
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
        // 保留 4 位以上有效数字时使用精确十进制展开（二进制浮点的完整有效位），
        // 低位超出 Double 的近似精度，必须按规范对精确值舍入
        if (digits != null && digits >= 3) {
            exactToExponential(value, digits)?.let { text ->
                return (if (negative) "-" else "") + text
            }
        }
        var exponent = floor(log10(value)).toInt()
        val decimals = digits ?: 15
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        var mantissa = value / 10.0.pow(exponent)
        mantissa = floor(mantissa * factor + 0.5) / factor
        if (mantissa >= 10.0) {
            mantissa /= 10.0
            exponent++
        }

        var text = toFixed(mantissa, decimals)
        if (digits == null && text.contains('.')) {
            text = text.trimEnd('0').trimEnd('.')
        }
        val sign = if (negative) "-" else ""
        val exponentSign = if (exponent >= 0) "+" else "-"
        return "$sign$text" + "e$exponentSign${abs(exponent)}"
    }

    /**
     * toPrecision 的简化实现。
     */
    private fun toPrecision(number: Double, precision: Int): String {
        if (!number.isFinite()) return NumberFormatting.toString(number)
        // 规范：±0 输出为正零格式（不含负号），p=1 时为 "0"
        if (number == 0.0) {
            return if (precision == 1) "0" else "0." + "0".repeat(precision - 1)
        }

        val negative = number < 0
        val value = abs(number)
        val exponent = floor(log10(value)).toInt()
        val sign = if (negative) "-" else ""

        if (exponent < -6 || exponent >= precision) {
            return sign + toExponential(value, precision - 1)
        }

        val decimals = (precision - exponent - 1).coerceAtLeast(0)
        return sign + toFixed(value, decimals)
    }
}
