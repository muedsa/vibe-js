package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Function 构造器与 Function.prototype。
 */
object FunctionBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val functionPrototype = intrinsics.functionPrototype

        val functionConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "Function",
            length = 1,
            prototypeObject = functionPrototype,
        ) { interpreter, _, args ->
            // 规范顺序：先 ToString 全部参数，再 ToString 函数体（异常顺序有测试覆盖）
            val params = if (args.size > 1) {
                args.dropLast(1).map { interpreter.toStringValue(it) }
            } else {
                emptyList()
            }
            val body = if (args.isEmpty()) "" else interpreter.toStringValue(args.last())
            interpreter.createDynamicFunction(params, body)
        }

        functionPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = functionConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )

        // ES2015 15.3.5.4：严格函数（以及绑定/内建函数）读取 caller/arguments 必须抛 TypeError
        val restrictedAccessor = JSNativeFunction(
            name = "",
            length = 0,
            prototype = functionPrototype,
        ) { interpreter, _, _ ->
            interpreter.typeError(
                "'caller', 'callee', and 'arguments' properties may not be accessed on strict mode functions"
            )
        }
        functionPrototype.defineAccessorProperty(
            key = PropertyKey.Str("caller"),
            get = restrictedAccessor,
            set = restrictedAccessor,
            enumerable = false,
            configurable = true,
        )
        functionPrototype.defineAccessorProperty(
            key = PropertyKey.Str("arguments"),
            get = restrictedAccessor,
            set = restrictedAccessor,
            enumerable = false,
            configurable = true,
        )

        // Function.prototype[@@hasInstance](V)：OrdinaryHasInstance
        functionPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolHasInstance),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.hasInstance]", 1) { interpreter, thisValue, args ->
                val value = args.getOrElse(0) { JSUndefined }
                // BoundFunction 的 @@hasInstance 委托给 [[BoundTargetFunction]]
                var target = thisValue
                while (target is JSNativeFunction && target.boundTarget != null) {
                    target = target.boundTarget
                }
                if (!interpreter.isCallable(target) || value !is JSObject) {
                    return@method JSBoolean.FALSE
                }
                val prototype = interpreter.getV(target, PropertyKey.PROTOTYPE)
                if (prototype !is JSObject) {
                    interpreter.typeError("Function has non-object prototype in instanceof check")
                }
                var current: JSObject? = value.prototype
                while (current != null) {
                    if (current === prototype) return@method JSBoolean.TRUE
                    current = current.prototype
                }
                JSBoolean.FALSE
            },
            writable = false,
            configurable = false,
        )

        // Function.prototype.call(thisArg, ...args)
        functionPrototype.defineDataProperty(
            key = PropertyKey.Str("call"),
            value = BuiltinHelpers.method(intrinsics, "call", 1) { interpreter, thisValue, args ->
                val thisArg = args.getOrNull(0) ?: JSUndefined
                val callArguments = if (args.size > 1) args.subList(1, args.size).toList() else emptyList()
                interpreter.callFunction(thisValue, thisArg, callArguments)
            },
        )

        // Function.prototype.apply(thisArg, argsArray)
        functionPrototype.defineDataProperty(
            key = PropertyKey.Str("apply"),
            value = BuiltinHelpers.method(intrinsics, "apply", 2) { interpreter, thisValue, args ->
                val thisArg = args.getOrNull(0) ?: JSUndefined
                val argumentArray = args.getOrNull(1)
                val callArguments = when (argumentArray) {
                    null, is JSUndefined, is JSNull -> emptyList()
                    is JSArray -> argumentArray.presentValues()
                    is JSObject -> {
                        val length = interpreter.toNumber(
                            interpreter.getV(argumentArray, PropertyKey.LENGTH)
                        ).toInt().coerceAtLeast(0)
                        val values = ArrayList<JSValue>(length)
                        for (index in 0 until length) {
                            values.add(
                                interpreter.getV(argumentArray, PropertyKey.Str(index.toString()))
                            )
                        }
                        values
                    }

                    else -> interpreter.typeError("CreateListFromArrayLike called on non-object")
                }
                interpreter.callFunction(thisValue, thisArg, callArguments)
            },
        )

        // Function.prototype.bind(thisArg, ...args)
        functionPrototype.defineDataProperty(
            key = PropertyKey.Str("bind"),
            value = BuiltinHelpers.method(intrinsics, "bind", 1) { interpreter, thisValue, args ->
                if (!interpreter.isCallable(thisValue)) {
                    interpreter.typeError("Bind must be called on a function")
                }
                val boundThis = args.getOrNull(0) ?: JSUndefined
                val boundArguments = if (args.size > 1) {
                    args.subList(1, args.size).toList()
                } else {
                    emptyList()
                }
                val targetObject = thisValue as? JSObject
                // 规范：目标 name 不是字符串时按空串处理
                val targetName = (targetObject?.getOwnDataValue(PropertyKey.NAME) as? JSString)?.value ?: ""
                // 绑定函数 length = max(0, 目标 length - 已绑定参数个数)
                val targetLength = (targetObject?.getOwnDataValue(PropertyKey.LENGTH) as? JSNumber)?.value ?: 0.0
                val boundLength = (targetLength - boundArguments.size).coerceAtLeast(0.0).toInt()
                // 目标是构造器时，绑定函数也可用 new 调用（转交给目标的 [[Construct]]）
                val constructable = interpreter.isConstructor(thisValue)
                JSNativeFunction(
                    name = "bound $targetName",
                    length = boundLength,
                    prototype = functionPrototype,
                    constructFunction = if (constructable) {
                        { inner, constructArgs ->
                            inner.construct(thisValue, boundArguments + constructArgs)
                        }
                    } else {
                        null
                    },
                    boundTarget = thisValue,
                ) { inner, _, callArgs ->
                    inner.callFunction(thisValue, boundThis, boundArguments + callArgs)
                }
            },
        )

        // Function.prototype.toString
        functionPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            value = BuiltinHelpers.method(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
                if (thisValue is JSFunction || thisValue is JSNativeFunction) {
                    JSString(thisValue.toPrimitiveString())
                } else {
                    interpreter.typeError("Function.prototype.toString requires that 'this' be a Function")
                }
            },
        )

        registry.defineGlobal("Function", functionConstructor)
    }
}
