package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.exception.formatErrorStack
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyDescriptor
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
                if (thisValue !is JSObject) {
                    interpreter.typeError("Error.prototype.toString called on non-object")
                }
                // name 未定义时取 "Error"，message 未定义时取空串
                val nameValue = interpreter.getV(thisValue, PropertyKey.NAME)
                val name = if (nameValue is JSUndefined) {
                    "Error"
                } else {
                    interpreter.toStringValue(nameValue)
                }
                val messageValue = interpreter.getV(thisValue, PropertyKey.MESSAGE)
                val message = if (messageValue is JSUndefined) {
                    ""
                } else {
                    interpreter.toStringValue(messageValue)
                }
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
        val aggregateErrorConstructor = BuiltinHelpers.constructorWithNewTarget(
            intrinsics = intrinsics,
            name = "AggregateError",
            length = 2,
            prototypeObject = aggregateErrorPrototype,
        ) { interpreter, _, args, newTarget ->
            val errorsValue = args.getOrElse(0) { JSUndefined }
            val messageArg = args.getOrElse(1) { JSUndefined }
            val objectValue = JSObject(
                prototype = interpreter.prototypeFromNewTarget(newTarget, aggregateErrorPrototype)
            )
            objectValue.isErrorData = true
            interpreter.captureErrorStack(objectValue, skipTopFrame = true)
            // 规范：仅在显式传入非 undefined 的 message 时创建自有属性
            if (messageArg !is JSUndefined) {
                objectValue.defineDataProperty(
                    key = PropertyKey.MESSAGE,
                    value = JSString(interpreter.toStringValue(messageArg)),
                    writable = true,
                    enumerable = false,
                    configurable = true,
                )
            }
            val errors = interpreter.iterateValues(errorsValue)
            objectValue.defineDataProperty(
                key = PropertyKey.Str("errors"),
                value = JSArray(errors.toMutableList(), prototype = intrinsics.arrayPrototype),
                writable = true,
                enumerable = false,
                configurable = true,
            )
            // options.cause：options 为对象且存在 cause 属性时复制（不可枚举）
            val options = args.getOrElse(2) { JSUndefined }
            if (options is JSObject) {
                val causeKey = PropertyKey.Str("cause")
                if (interpreter.hasProperty(options, causeKey)) {
                    objectValue.defineDataProperty(
                        key = causeKey,
                        value = interpreter.getV(options, causeKey),
                        writable = true,
                        enumerable = false,
                        configurable = true,
                    )
                }
            }
            objectValue
        }
        intrinsics.aggregateErrorConstructor = aggregateErrorConstructor

        // SuppressedError(error, suppressed, message)：message 仅在显式传入时创建
        val suppressedErrorConstructor = BuiltinHelpers.constructorWithNewTarget(
            intrinsics = intrinsics,
            name = "SuppressedError",
            length = 3,
            prototypeObject = intrinsics.suppressedErrorPrototype,
        ) { interpreter, _, args, newTarget ->
            val errorValue = args.getOrElse(0) { JSUndefined }
            val suppressedValue = args.getOrElse(1) { JSUndefined }
            val messageArg = args.getOrElse(2) { JSUndefined }
            val objectValue = JSObject(
                prototype = interpreter.prototypeFromNewTarget(
                    newTarget,
                    intrinsics.suppressedErrorPrototype,
                )
            )
            objectValue.isErrorData = true
            interpreter.captureErrorStack(objectValue, skipTopFrame = true)
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

        errorConstructor.defineDataProperty(
            key = PropertyKey.Str("isError"),
            value = BuiltinHelpers.method(intrinsics, "isError", 1) { _, _, args ->
                val value = args.getOrElse(0) { JSUndefined }
                JSBoolean.getJsBoolean(value is JSObject && value.isErrorData)
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        // Error.prototype.stack：实现定义的堆栈字符串访问器
        val stackKey = PropertyKey.Str("stack")
        intrinsics.errorPrototype.defineAccessorProperty(
            key = stackKey,
            get = BuiltinHelpers.method(intrinsics, "get stack", 0) { interpreter, thisValue, _ ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("get Error.prototype.stack called on non-object")
                }
                if (!thisValue.isErrorData) return@method JSUndefined
                val name = readStackText(interpreter, thisValue, PropertyKey.NAME, "Error")
                val message = readStackText(interpreter, thisValue, PropertyKey.MESSAGE, "")
                val header = when {
                    name.isEmpty() -> message
                    message.isEmpty() -> name
                    else -> "$name: $message"
                }
                JSString(formatErrorStack(header, thisValue.errorStack ?: emptyList()))
            },
            set = BuiltinHelpers.method(intrinsics, "set stack", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("set Error.prototype.stack called on non-object")
                }
                val value = args.getOrElse(0) { JSUndefined }
                if (value !is JSString) {
                    interpreter.typeError("Error.prototype.stack must be a string")
                }
                if (thisValue === intrinsics.errorPrototype) {
                    interpreter.typeError("Cannot set Error.prototype.stack")
                }
                if (thisValue.getOwnProperty(stackKey) == null) {
                    val defined = interpreter.defineOwnPropertyOn(
                        thisValue,
                        stackKey,
                        PropertyDescriptor.data(
                            value,
                            writable = true,
                            enumerable = true,
                            configurable = true,
                        ),
                    )
                    if (!defined) interpreter.typeError("Cannot create Error.prototype.stack")
                } else {
                    interpreter.putV(thisValue, stackKey, value, strict = true)
                }
                JSUndefined
            },
            enumerable = false,
            configurable = true,
        )
    }

    /** 读取 name/message 文本，异常或缺失时回退。 */
    private suspend fun readStackText(
        interpreter: Interpreter,
        objectValue: JSObject,
        key: PropertyKey,
        fallback: String,
    ): String {
        val value = try {
            interpreter.getV(objectValue, key)
        } catch (_: JSException) {
            return fallback
        }
        if (value is JSUndefined) return fallback
        return try {
            interpreter.toStringValue(value)
        } catch (_: JSException) {
            fallback
        }
    }

    private fun createErrorConstructor(
        registry: BuiltinRegistry,
        name: String,
        prototype: JSObject,
    ): JSNativeFunction {
        val intrinsics = registry.intrinsics
        return BuiltinHelpers.constructorWithNewTarget(
            intrinsics = intrinsics,
            name = name,
            length = 1,
            prototypeObject = prototype,
        ) { interpreter, _, args, newTarget ->
            // 规范：message 仅在传入非 undefined 时定义为自有属性
            val messageArg = args.getOrElse(0) { JSUndefined }
            val hasMessage = messageArg !is JSUndefined
            val message = if (hasMessage) interpreter.toStringValue(messageArg) else ""
            val error = JSError(
                errorName = name,
                messageText = message,
                prototype = interpreter.prototypeFromNewTarget(newTarget, prototype),
                defineMessage = hasMessage,
            )
            interpreter.captureErrorStack(error, skipTopFrame = true)
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
