package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSBoxedPrimitive
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Boolean 构造器与 Boolean.prototype。
 */
object BooleanBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val booleanPrototype = intrinsics.booleanPrototype

        val booleanConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "Boolean",
            length = 1,
            prototypeObject = booleanPrototype,
        ) { interpreter, _, args ->
            JSBoolean.getJsBoolean(interpreter.toBoolean(args.getOrElse(0) { JSUndefined }))
        }

        booleanPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = booleanConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )

        booleanPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            value = BuiltinHelpers.method(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
                JSString(booleanText(interpreter, thisValue))
            },
        )

        booleanPrototype.defineDataProperty(
            key = PropertyKey.Str("valueOf"),
            value = BuiltinHelpers.method(intrinsics, "valueOf", 0) { interpreter, thisValue, _ ->
                JSBoolean.getJsBoolean(booleanValue(interpreter, thisValue))
            },
        )


        registry.defineGlobal("Boolean", booleanConstructor)
    }

    private fun booleanValue(interpreter: com.muedsa.js.runtime.Interpreter, thisValue: com.muedsa.js.runtime.value.JSValue): Boolean {
        return when (thisValue) {
            is JSBoolean -> thisValue.value
            is JSBoxedPrimitive -> (thisValue.primitive as? JSBoolean)?.value
                ?: interpreter.typeError("Boolean.prototype method requires that 'this' be a Boolean")
            // Boolean.prototype 自身代表 false
            interpreter.intrinsics.booleanPrototype -> false
            else -> interpreter.typeError("Boolean.prototype method requires that 'this' be a Boolean")
        }
    }

    private fun booleanText(interpreter: com.muedsa.js.runtime.Interpreter, thisValue: com.muedsa.js.runtime.value.JSValue): String =
        booleanValue(interpreter, thisValue).toString()
}
