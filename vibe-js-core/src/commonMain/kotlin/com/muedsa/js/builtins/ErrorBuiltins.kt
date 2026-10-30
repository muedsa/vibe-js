package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Error 及其标准子类。
 */
object ErrorBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val functionPrototype = intrinsics.functionPrototype

        // Error.prototype.toString
        intrinsics.errorPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = com.muedsa.js.runtime.value.JSString("Error"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        intrinsics.errorPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            value = BuiltinHelpers.method(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
                val objectValue = interpreter.toObject(thisValue)
                val name = interpreter.toStringValue(
                    interpreter.getV(objectValue, PropertyKey.NAME)
                )
                val message = interpreter.toStringValue(
                    interpreter.getV(objectValue, PropertyKey.MESSAGE)
                )
                JSString(
                    when {
                        name.isEmpty() -> message
                        message.isEmpty() -> name
                        else -> "$name: $message"
                    }
                )
            },
        )

        val errorConstructor = createErrorConstructor(
            registry, "Error", intrinsics.errorPrototype,
        )
        val typeErrorConstructor = createErrorConstructor(
            registry, "TypeError", intrinsics.typeErrorPrototype,
        )
        val rangeErrorConstructor = createErrorConstructor(
            registry, "RangeError", intrinsics.rangeErrorPrototype,
        )
        val referenceErrorConstructor = createErrorConstructor(
            registry, "ReferenceError", intrinsics.referenceErrorPrototype,
        )
        val syntaxErrorConstructor = createErrorConstructor(
            registry, "SyntaxError", intrinsics.syntaxErrorPrototype,
        )
        val evalErrorConstructor = createErrorConstructor(
            registry, "EvalError", intrinsics.evalErrorPrototype,
        )
        val uriErrorConstructor = createErrorConstructor(
            registry, "URIError", intrinsics.uriErrorPrototype,
        )

        registry.defineGlobal("Error", errorConstructor)
        registry.defineGlobal("TypeError", typeErrorConstructor)
        registry.defineGlobal("RangeError", rangeErrorConstructor)
        registry.defineGlobal("ReferenceError", referenceErrorConstructor)
        registry.defineGlobal("SyntaxError", syntaxErrorConstructor)
        registry.defineGlobal("EvalError", evalErrorConstructor)
        registry.defineGlobal("URIError", uriErrorConstructor)
    }

    private fun createErrorConstructor(
        registry: BuiltinRegistry,
        name: String,
        prototype: JSObject,
    ): JSNativeFunction {
        val intrinsics = registry.intrinsics
        return BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = name,
            length = 1,
            prototypeObject = prototype,
        ) { interpreter, _, args ->
            val message = if (args.isEmpty() || args[0] is JSUndefined) {
                ""
            } else {
                interpreter.toStringValue(args[0])
            }
            JSError(name, message, prototype)
        }
    }
}
