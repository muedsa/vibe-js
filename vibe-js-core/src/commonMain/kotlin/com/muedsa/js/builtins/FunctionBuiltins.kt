package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
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
            val body = if (args.isEmpty()) "" else interpreter.toStringValue(args.last())
            val params = if (args.size > 1) {
                args.dropLast(1).map { interpreter.toStringValue(it) }
            } else {
                emptyList()
            }
            interpreter.createDynamicFunction(params, body)
        }

        functionPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = functionConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
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
                    is JSArray -> argumentArray.elements.filterNotNull()
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
                val targetName = (thisValue as? JSObject)
                    ?.getOwnDataValue(PropertyKey.NAME)
                    ?.toPrimitiveString() ?: "function"
                BuiltinHelpers.method(intrinsics, "bound $targetName", 0) { inner, _, callArgs ->
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
