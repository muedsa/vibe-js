package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.exception.JSException

data class JSNumber(val value: Double) : JSObject(
    prototype = NumberPrototype
) {
    override val isPrimitive: Boolean = true
    override fun toPrimitiveBoolean() = value != 0.0 && !value.isNaN()
    override fun toPrimitiveNumber() = value
    override fun toPrimitiveString() = if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        value.toString()
    }

    companion object {
        val MAX_VALUE = JSNumber(Double.MAX_VALUE)
        val MIN_VALUE = JSNumber(Double.MIN_VALUE)
        val POSITIVE_INFINITY = JSNumber(Double.POSITIVE_INFINITY)
        val NEGATIVE_INFINITY = JSNumber(Double.NEGATIVE_INFINITY)
        val NaN = JSNumber(Double.NaN)
        val ZERO = JSNumber(0.0)
    }
}

val NumberPrototype = JSNativeFunction(
    name = "",
    lazyProperties = lazy {
        mutableMapOf(
            "toExponential" to JSNativeFunction("Number.prototype.toExponential") { _, thisValue, args ->
                val thisNumber = convertJSValueToJSNumber(thisValue, "Number.prototype.toExponential")
                TODO()
            },
            "toFixed" to JSNativeFunction("Number.prototype.toFixed") { _, thisValue, args ->
                val thisNumber = convertJSValueToJSNumber(thisValue, "Number.prototype.toFixed")
                TODO()
            },
            "toPrecision" to JSNativeFunction("Number.prototype.toPrecision") { _, thisValue, args ->
                val thisNumber = convertJSValueToJSNumber(thisValue, "Number.prototype.toPrecision")
                TODO()
            },
            "toString" to JSNativeFunction("toString") { _, thisValue, _ ->
                JSString(thisValue.toPrimitiveString())
            },
        )
    },
    prototype = ObjectPrototype
)

val NumberConstructor = JSNativeFunction(
    name = "Number",
    lazyProperties = lazy {
        mutableMapOf(
            "MAX_VALUE" to JSNumber.MAX_VALUE,
            "MIN_VALUE" to JSNumber.MIN_VALUE,
            "POSITIVE_INFINITY" to JSNumber.POSITIVE_INFINITY,
            "NEGATIVE_INFINITY" to JSNumber.NEGATIVE_INFINITY,
            "NaN" to JSNumber.NaN,
            "isNaN" to JSNativeFunction("isNaN") { interpreter, _, args ->
                val value = interpreter.getPrimitiveNumber(args.getOrElse(0) { JSUndefined })
                JSBoolean.getJsBoolean(value.isNaN())
            },
            "isFinite" to JSNativeFunction("isFinite") { interpreter, _, args ->
                JSBoolean.getJsBoolean(interpreter.getPrimitiveNumber(args.getOrElse(0) { JSUndefined }).isFinite())
            },
            "isInteger" to JSNativeFunction("isInteger") { interpreter, _, args ->
                val arg = args.getOrElse(0) { JSUndefined }
                if (arg !is JSNumber) {
                    JSBoolean.False
                } else {
                    JSBoolean.getJsBoolean(arg.value.isFinite() && arg.value % 1.0 == 0.0)
                }
            },
            "parseInt" to JSNativeFunction("parseInt") { interpreter, _, args ->
                val inputString = interpreter.getPrimitiveString(args.getOrElse(0) { JSUndefined })
                val radixArg = args.getOrElse(1) { JSUndefined }

                // 1. Trim leading whitespace
                var s = inputString.trimStart()

                // 2. Sign
                var sign = 1.0
                if (s.isNotEmpty()) {
                    if (s[0] == '-') {
                        sign = -1.0
                        s = s.substring(1)
                    } else if (s[0] == '+') {
                        s = s.substring(1)
                    }
                }

                // 3. Radix
                var R = if (radixArg == JSUndefined) 0 else interpreter.getPrimitiveNumber(radixArg).toInt()

                var stripPrefix = false
                if (R == 0) {
                    if (s.length >= 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) {
                        R = 16
                        stripPrefix = true
                    } else {
                        R = 10
                    }
                } else if (R == 16) {
                    stripPrefix = true
                } else if (R < 2 || R > 36) {
                    return@JSNativeFunction JSNumber.NaN
                }

                if (stripPrefix && s.length >= 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) {
                    s = s.substring(2)
                }

                // 4. Parsing digits
                var value = 0.0
                var hasDigits = false

                for (char in s) {
                    val digit = digitValue(char)
                    if (digit != -1 && digit < R) {
                        value = value * R + digit
                        hasDigits = true
                    } else {
                        break
                    }
                }

                if (!hasDigits) {
                    JSNumber.NaN
                } else {
                    JSNumber(sign * value)
                }
            },
            "parseFloat" to JSNativeFunction("parseFloat") { interpreter, _, args ->
                JSNumber(interpreter.getPrimitiveNumber(args.getOrElse(0) { JSUndefined }))
            },
            "[Symbol.toStringTag]" to JSString("Number")
        )
    }
) { interpreter, _, args ->
    if (args.isEmpty()) JSNumber.ZERO else JSNumber(interpreter.getPrimitiveNumber(args[0]))
}

fun convertJSValueToJSNumber(value: JSValue, callee: String): JSNumber {
    return value as? JSNumber
        ?: throw JSException(JSError("TypeError", "$callee requires that 'this' be a Number"))
}

private fun digitValue(c: Char): Int {
    return when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'z' -> c - 'a' + 10
        in 'A'..'Z' -> c - 'A' + 10
        else -> -1
    }
}
