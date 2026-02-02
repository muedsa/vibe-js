package com.muedsa.js.runtime.internal

import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSUndefined
import kotlin.math.*
import kotlin.random.Random

val JSMath = JSObject(
    mutableMapOf(
        "E" to JSNumber(E),
        "LN2" to JSNumber(ln(2.0)),
        "LN10" to JSNumber(ln(10.0)),
        "LOG2E" to JSNumber(log2(E)),
        "LOG10E" to JSNumber(log10(E)),
        "PI" to JSNumber(PI),
        "SQRT1_2" to JSNumber(sqrt(0.5)),
        "SQRT2" to JSNumber(sqrt(2.0)),

        "abs" to JSNativeFunction("abs") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(abs(value))
        },
        "acos" to JSNativeFunction("acos") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(acos(value))
        },
        "acosh" to JSNativeFunction("acosh") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(acosh(value))
        },
        "asin" to JSNativeFunction("asin") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(asin(value))
        },
        "asinh" to JSNativeFunction("asinh") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(asinh(value))
        },
        "atan" to JSNativeFunction("atan") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(atan(value))
        },
        "atanh" to JSNativeFunction("atanh") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(atanh(value))
        },
        "atan2" to JSNativeFunction("atan2") { interpreter, _, args ->
            val y = interpreter.getPrimitiveNumber(args.getOrElse(0) { JSUndefined })
            val x = interpreter.getPrimitiveNumber(args.getOrElse(1) { JSUndefined })
            JSNumber(atan2(y, x))
        },
        "cbrt" to JSNativeFunction("cbrt") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            // Kotlin 1.8+ has cbrt, but for broader compatibility let's use pow
            JSNumber(value.pow(1.0 / 3.0))
        },
        "ceil" to JSNativeFunction("ceil") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(ceil(value))
        },
        "clz32" to JSNativeFunction("clz32") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg).toLong().toUInt()
            JSNumber(value.countLeadingZeroBits().toDouble())
        },
        "cos" to JSNativeFunction("cos") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(cos(value))
        },
        "cosh" to JSNativeFunction("cosh") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(cosh(value))
        },
        "exp" to JSNativeFunction("exp") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(exp(value))
        },
        "expm1" to JSNativeFunction("expm1") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(expm1(value))
        },
        "floor" to JSNativeFunction("floor") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(floor(value))
        },
        "fround" to JSNativeFunction("fround") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(value.toFloat().toDouble())
        },
        "hypot" to JSNativeFunction("hypot") { interpreter, _, args ->
            if (args.isEmpty()) {
                JSNumber(0.0)
            } else {
                // hypot(x, y) is standard, generalized hypot is sqrt(sum(squares))
                // But Kotlin's hypot takes 2 arguments.
                // Generalized hypot implementation:
                var sum = 0.0
                for (arg in args) {
                    val value = interpreter.getPrimitiveNumber(arg)
                    if (value.isInfinite()) return@JSNativeFunction JSNumber(Double.POSITIVE_INFINITY)
                    if (value.isNaN()) return@JSNativeFunction JSNumber(Double.NaN)
                    sum += value * value
                }
                JSNumber(sqrt(sum))
            }
        },
        "imul" to JSNativeFunction("imul") { interpreter, _, args ->
            val a = interpreter.getPrimitiveNumber(args.getOrElse(0) { JSUndefined }).toLong().toInt()
            val b = interpreter.getPrimitiveNumber(args.getOrElse(1) { JSUndefined }).toLong().toInt()
            JSNumber((a * b).toDouble())
        },
        "log" to JSNativeFunction("log") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(ln(value))
        },
        "log1p" to JSNativeFunction("log1p") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(ln1p(value))
        },
        "log10" to JSNativeFunction("log10") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(log10(value))
        },
        "log2" to JSNativeFunction("log2") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(log2(value))
        },
        "max" to JSNativeFunction("max") { interpreter, _, args ->
            if (args.isEmpty()) {
                JSNumber(Double.NEGATIVE_INFINITY)
            } else {
                var maxValue = Double.NEGATIVE_INFINITY
                for (arg in args) {
                    val value = interpreter.getPrimitiveNumber(arg)
                    if (value.isNaN()) return@JSNativeFunction JSNumber(Double.NaN)
                    maxValue = max(maxValue, value)
                }
                JSNumber(maxValue)
            }
        },
        "min" to JSNativeFunction("min") { interpreter, _, args ->
            if (args.isEmpty()) {
                JSNumber(Double.POSITIVE_INFINITY)
            } else {
                var minValue = Double.POSITIVE_INFINITY
                for (arg in args) {
                    val value = interpreter.getPrimitiveNumber(arg)
                    if (value.isNaN()) return@JSNativeFunction JSNumber(Double.NaN)
                    minValue = min(minValue, value)
                }
                JSNumber(minValue)
            }
        },
        "pow" to JSNativeFunction("pow") { interpreter, _, args ->
            val base = interpreter.getPrimitiveNumber(args.getOrElse(0) { JSUndefined })
            val exponent = interpreter.getPrimitiveNumber(args.getOrElse(1) { JSUndefined })
            JSNumber(base.pow(exponent))
        },
        "random" to JSNativeFunction("random") { _, _, _ ->
            JSNumber(Random.nextDouble())
        },
        "round" to JSNativeFunction("round") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            // JS Math.round behaves slightly differently than Kotlin round for negative numbers ending in .5
            // JS: round(-1.5) -> -1, round(-1.6) -> -2
            // Kotlin round: rounds to nearest even integer for ties
            // We should use floor(x + 0.5)
            JSNumber(floor(value + 0.5))
        },
        "sign" to JSNativeFunction("sign") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(sign(value))
        },
        "sin" to JSNativeFunction("sin") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(sin(value))
        },
        "sinh" to JSNativeFunction("sinh") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(sinh(value))
        },
        "sqrt" to JSNativeFunction("sqrt") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(sqrt(value))
        },
        "tan" to JSNativeFunction("tan") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(tan(value))
        },
        "tanh" to JSNativeFunction("tanh") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(tanh(value))
        },
        "trunc" to JSNativeFunction("trunc") { interpreter, _, args ->
            val arg = args.getOrElse(0) { JSUndefined }
            val value = interpreter.getPrimitiveNumber(arg)
            JSNumber(truncate(value))
        },
    )
)
