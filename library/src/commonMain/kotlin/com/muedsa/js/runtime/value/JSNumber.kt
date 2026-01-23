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
                val argStr = interpreter.getPrimitiveString(args.getOrElse(0) { JSUndefined })
                val regex = Regex("""^\s*([+-]?\d+)""")
                val match = regex.find(argStr)
                if (match != null) {
                    val numberStr = match.groupValues[1]
                    JSNumber(numberStr.toDouble())
                } else {
                    JSNumber.NaN
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