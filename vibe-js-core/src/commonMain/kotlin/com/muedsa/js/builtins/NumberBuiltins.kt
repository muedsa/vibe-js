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

        numberConstructor.defineDataProperty(
            key = PropertyKey.Str("parseInt"),
            value = BuiltinHelpers.method(intrinsics, "parseInt", 2) { interpreter, _, args ->
                NumberParsing.parseInt(interpreter, args)
            },
        )

        numberConstructor.defineDataProperty(
            key = PropertyKey.Str("parseFloat"),
            value = BuiltinHelpers.method(intrinsics, "parseFloat", 1) { interpreter, _, args ->
                NumberParsing.parseFloat(interpreter, args)
            },
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
        if (number == 0.0) {
            val sign = if (1.0 / number < 0) "-" else ""
            return if (precision == 1) "0" else sign + "0." + "0".repeat(precision - 1)
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
