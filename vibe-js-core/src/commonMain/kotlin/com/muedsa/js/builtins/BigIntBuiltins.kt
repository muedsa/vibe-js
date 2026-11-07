package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.PreferredType
import com.muedsa.js.runtime.value.JSBigInt
import com.muedsa.js.runtime.value.JSBoxedPrimitive
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * BigInt 构造器与 BigInt.prototype。
 *
 * 规范要点：
 * - BigInt 具有 [[Construct]]（因此 `isConstructor(BigInt)` 为 true），但 NewTarget
 *   不是 undefined 时立即抛 TypeError，所以 `new BigInt(1)` 实际不可用；
 * - `BigInt(value)` 只做一次 ToPrimitive，Number 结果走 NumberToBigInt（非整数抛 RangeError），
 *   其余走 ToBigInt（非法字符串抛 SyntaxError）；
 * - BigInt.prototype 的 [[Prototype]] 是 Object.prototype，并提供 toString/toLocaleString/valueOf
 *   与 `@@toStringTag = "BigInt"`。
 */
object BigIntBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val bigIntPrototype = intrinsics.bigIntPrototype

        val bigIntConstructor = BuiltinHelpers.constructorWithNewTarget(
            intrinsics = intrinsics,
            name = "BigInt",
            length = 1,
            prototypeObject = bigIntPrototype,
        ) { interpreter, _, args, newTarget ->
            // 规范顺序：先检查 NewTarget，再对参数做任何转换
            if (newTarget !is JSUndefined) {
                interpreter.typeError("BigInt is not a constructor")
            }
            val value = args.getOrElse(0) { JSUndefined }
            // 规范：BigInt(value) 只做一次 ToPrimitive(value, number)
            val primitive = interpreter.conversions.toPrimitive(value, PreferredType.NUMBER)
            when (primitive) {
                is JSBigInt -> primitive
                is JSNumber -> JSBigInt.fromDoubleExact(primitive.value)
                    ?: interpreter.rangeError(
                        "The number ${primitive.value} cannot be converted to a BigInt " +
                            "because it is not an integer"
                    )

                else -> interpreter.conversions.toBigInt(primitive)
            }
        }

        bigIntPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("BigInt"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        installPrototypeMethods(registry)
        installStaticMethods(registry, bigIntConstructor)

        registry.defineGlobal("BigInt", bigIntConstructor)
    }

    private fun installPrototypeMethods(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val bigIntPrototype = intrinsics.bigIntPrototype

        bigIntPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            // 规范：radix 是可选参数，因此 length 为 0
            value = BuiltinHelpers.method(intrinsics, "toString", 0) { interpreter, thisValue, args ->
                val value = thisBigIntValue(interpreter, thisValue, "BigInt.prototype.toString")
                val radixArgument = args.getOrNull(0)
                val radix = if (radixArgument == null || radixArgument is JSUndefined) {
                    10.0
                } else {
                    // 规范：radix 先 ToIntegerOrInfinity；NaN 视为 0，因此会触发 RangeError
                    interpreter.conversions.toIntegerOrInfinity(
                        interpreter.toNumber(radixArgument)
                    )
                }
                if (radix < 2.0 || radix > 36.0) {
                    interpreter.rangeError("toString() radix must be between 2 and 36")
                }
                JSString(value.toString(radix.toInt()))
            },
        )

        bigIntPrototype.defineDataProperty(
            key = PropertyKey.Str("toLocaleString"),
            value = BuiltinHelpers.method(intrinsics, "toLocaleString", 0) { interpreter, thisValue, _ ->
                // 规范允许实现自定义本地化格式；默认按十进制输出
                JSString(
                    thisBigIntValue(
                        interpreter, thisValue, "BigInt.prototype.toLocaleString",
                    ).toString(10)
                )
            },
        )

        bigIntPrototype.defineDataProperty(
            key = PropertyKey.Str("valueOf"),
            value = BuiltinHelpers.method(intrinsics, "valueOf", 0) { interpreter, thisValue, _ ->
                thisBigIntValue(interpreter, thisValue, "BigInt.prototype.valueOf")
            },
        )
    }

    private fun installStaticMethods(
        registry: BuiltinRegistry,
        bigIntConstructor: com.muedsa.js.runtime.value.JSNativeFunction,
    ) {
        val intrinsics = registry.intrinsics

        bigIntConstructor.defineDataProperty(
            key = PropertyKey.Str("asIntN"),
            value = BuiltinHelpers.method(intrinsics, "asIntN", 2) { interpreter, _, args ->
                // 规范顺序：先 ToIndex(bits)，再 ToBigInt(bigint)
                val bits = interpreter.toIndex(args.getOrElse(0) { JSUndefined })
                val value = interpreter.conversions.toBigInt(args.getOrElse(1) { JSUndefined })
                value.asIntN(bits) ?: interpreter.rangeError("Maximum BigInt size exceeded")
            },
        )

        bigIntConstructor.defineDataProperty(
            key = PropertyKey.Str("asUintN"),
            value = BuiltinHelpers.method(intrinsics, "asUintN", 2) { interpreter, _, args ->
                val bits = interpreter.toIndex(args.getOrElse(0) { JSUndefined })
                val value = interpreter.conversions.toBigInt(args.getOrElse(1) { JSUndefined })
                value.asUintN(bits) ?: interpreter.rangeError("Maximum BigInt size exceeded")
            },
        )
    }

    /**
     * thisBigIntValue：接收 BigInt 原始值或带 [[BigIntData]] 的包装对象。
     *
     * 与 Number/Boolean 不同，BigInt.prototype 自身没有内部数据槽，传入时抛 TypeError。
     */
    private fun thisBigIntValue(
        interpreter: Interpreter,
        thisValue: JSValue,
        name: String,
    ): JSBigInt = when (thisValue) {
        is JSBigInt -> thisValue
        is JSBoxedPrimitive -> thisValue.primitive as? JSBigInt
            ?: interpreter.typeError("$name requires that 'this' be a BigInt")

        else -> interpreter.typeError("$name requires that 'this' be a BigInt")
    }
}
