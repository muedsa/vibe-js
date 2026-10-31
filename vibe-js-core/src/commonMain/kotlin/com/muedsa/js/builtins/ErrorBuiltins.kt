package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
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
        // AggregateError(errors, message)：errors 为可迭代对象，实例带 errors 数组
        val aggregateErrorPrototype = JSObject(prototype = intrinsics.errorPrototype)
        aggregateErrorPrototype.defineDataProperty(
            key = PropertyKey.NAME,
            value = JSString("AggregateError"),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        aggregateErrorPrototype.defineDataProperty(
            key = PropertyKey.MESSAGE,
            value = JSString(""),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        val aggregateErrorConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "AggregateError",
            length = 2,
            prototypeObject = aggregateErrorPrototype,
        ) { interpreter, _, args ->
            val errorsValue = args.getOrElse(0) { JSUndefined }
            val message = if (args.size < 2 || args[1] is JSUndefined) {
                ""
            } else {
                interpreter.toStringValue(args[1])
            }
            val errors = interpreter.iterateValues(errorsValue)
            val error = JSError("AggregateError", message, aggregateErrorPrototype)
            error.defineDataProperty(
                key = PropertyKey.Str("errors"),
                value = JSArray(errors.toMutableList(), prototype = intrinsics.arrayPrototype),
                writable = true,
                enumerable = false,
                configurable = true,
            )
            error
        }
        intrinsics.aggregateErrorConstructor = aggregateErrorConstructor

        // SuppressedError(error, suppressed, message)：message 仅在显式传入时创建
        val suppressedErrorConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "SuppressedError",
            length = 3,
            prototypeObject = intrinsics.suppressedErrorPrototype,
        ) { interpreter, _, args ->
            val errorValue = args.getOrElse(0) { JSUndefined }
            val suppressedValue = args.getOrElse(1) { JSUndefined }
            val messageArg = args.getOrElse(2) { JSUndefined }
            val objectValue = JSObject(prototype = intrinsics.suppressedErrorPrototype)
            if (messageArg !is JSUndefined) {
                objectValue.defineDataProperty(
                    key = PropertyKey.MESSAGE,
                    value = JSString(interpreter.toStringValue(messageArg)),
                    writable = true,
                    enumerable = false,
                    configurable = true,
                )
            }
            objectValue.defineDataProperty(
                key = PropertyKey.Str("error"),
                value = errorValue,
                writable = true,
                enumerable = false,
                configurable = true,
            )
            objectValue.defineDataProperty(
                key = PropertyKey.Str("suppressed"),
                value = suppressedValue,
                writable = true,
                enumerable = false,
                configurable = true,
            )
            objectValue
        }

        // ES5 15.11.3：标准错误子构造器的 [[Prototype]] 是 Error 构造器
        for (constructor in listOf(
            typeErrorConstructor, rangeErrorConstructor, referenceErrorConstructor,
            syntaxErrorConstructor, evalErrorConstructor, uriErrorConstructor,
            suppressedErrorConstructor, aggregateErrorConstructor,
        )) {
            constructor.prototype = errorConstructor
        }

        registry.defineGlobal("Error", errorConstructor)
        registry.defineGlobal("TypeError", typeErrorConstructor)
        registry.defineGlobal("RangeError", rangeErrorConstructor)
        registry.defineGlobal("ReferenceError", referenceErrorConstructor)
        registry.defineGlobal("SyntaxError", syntaxErrorConstructor)
        registry.defineGlobal("EvalError", evalErrorConstructor)
        registry.defineGlobal("URIError", uriErrorConstructor)
        registry.defineGlobal("SuppressedError", suppressedErrorConstructor)
        registry.defineGlobal("AggregateError", aggregateErrorConstructor)
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
            val error = JSError(name, message, prototype)
            // options.cause：存在该属性时复制到错误实例（不可枚举）
            val options = args.getOrNull(1)
            if (options != null && options !is JSUndefined) {
                val optionsObject = interpreter.toObject(options)
                val causeKey = PropertyKey.Str("cause")
                if (interpreter.hasProperty(optionsObject, causeKey)) {
                    val cause = interpreter.getV(optionsObject, causeKey)
                    error.defineDataProperty(
                        key = causeKey,
                        value = cause,
                        writable = true,
                        enumerable = false,
                        configurable = true,
                    )
                }
            }
            error
        }
    }
}
