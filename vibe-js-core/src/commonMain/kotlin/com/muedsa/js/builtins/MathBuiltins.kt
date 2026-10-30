package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSUndefined
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
        unary("round") { value -> floor(value + 0.5) }
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
            key = PropertyKey.Str("random"),
            value = BuiltinHelpers.method(intrinsics, "random", 0) { _, _, _ ->
                JSNumber(Random.nextDouble())
            },
        )
    }
}
