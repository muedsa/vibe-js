package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.Intrinsics
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyDescriptor
import com.muedsa.js.runtime.value.PropertyKey

/**
 * ES2025 Iterator helpers 与组合方法的安装逻辑。
 *
 * 从 [IteratorBuiltins] 拆出：helper 与组合方法数量多、彼此独立，单独成文件后
 * 迭代器协议核心（构造器、数组/字符串迭代器、next/return 分发）更易阅读。
 * 共享基础设施（DirectIterator / getIteratorDirect / nextValue / createHelper 等）
 * 仍位于 IteratorBuiltins，并以 internal 暴露给本文件。
 */
internal object IteratorHelperBuiltins {

    /** 安装 %IteratorPrototype% 上的辅助方法。 */
    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val prototype = intrinsics.iteratorPrototype

        prototype.defineDataProperty(
            PropertyKey.Str("chunks"),
            BuiltinHelpers.method(intrinsics, "chunks", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.chunks called on non-object")
                }
                val chunkSize = IteratorBuiltins.validateChunkSize(
                    interpreter,
                    args.getOrElse(0) { JSUndefined },
                    thisValue,
                )
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                IteratorBuiltins.createHelper(interpreter, record) { inner ->
                    val buffer = ArrayList<JSValue>()
                    while (buffer.size.toLong() < chunkSize) {
                        val value = IteratorBuiltins.nextValue(inner, record)
                        if (value == null) {
                            if (buffer.isEmpty()) return@createHelper null
                            return@createHelper JSArray(
                                buffer.toMutableList(),
                                prototype = intrinsics.arrayPrototype,
                            )
                        }
                        buffer.add(value)
                    }
                    JSArray(buffer.toMutableList(), prototype = intrinsics.arrayPrototype)
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("windows"),
            BuiltinHelpers.method(intrinsics, "windows", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.windows called on non-object")
                }
                val windowSize = IteratorBuiltins.validateChunkSize(
                    interpreter,
                    args.getOrElse(0) { JSUndefined },
                    thisValue,
                )
                val undersizedValue = args.getOrElse(1) { JSUndefined }
                val undersized = if (undersizedValue is JSUndefined) {
                    "only-full"
                } else if (undersizedValue is JSString &&
                    undersizedValue.value in setOf("only-full", "allow-partial")
                ) {
                    undersizedValue.value
                } else {
                    IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Invalid undersized mode")
                }
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                val buffer = ArrayList<JSValue>()
                var yieldedPartial = false
                IteratorBuiltins.createHelper(interpreter, record) { inner ->
                    while (true) {
                        val value = IteratorBuiltins.nextValue(inner, record)
                        if (value == null) {
                            if (undersized == "allow-partial" && !yieldedPartial &&
                                buffer.isNotEmpty() && buffer.size.toLong() < windowSize
                            ) {
                                yieldedPartial = true
                                return@createHelper JSArray(
                                    buffer.toMutableList(),
                                    prototype = intrinsics.arrayPrototype,
                                )
                            }
                            return@createHelper null
                        }
                        if (buffer.size.toLong() == windowSize) {
                            buffer.removeAt(0)
                        }
                        buffer.add(value)
                        if (buffer.size.toLong() == windowSize) {
                            return@createHelper JSArray(
                                buffer.toMutableList(),
                                prototype = intrinsics.arrayPrototype,
                            )
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("includes"),
            BuiltinHelpers.method(intrinsics, "includes", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.includes called on non-object")
                }
                val searchElement = args.getOrElse(0) { JSUndefined }
                val skipValue = args.getOrElse(1) { JSUndefined }
                var toSkip = 0.0
                if (skipValue !is JSUndefined) {
                    if (skipValue !is JSNumber) {
                        IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                        interpreter.typeError("skippedElements must be a Number")
                    }
                    val number = skipValue.value
                    if (!number.isInfinite() &&
                        (!number.isFinite() || kotlin.math.floor(number) != number)
                    ) {
                        IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                        interpreter.typeError("skippedElements must be an integral Number")
                    }
                    if (number < 0.0) {
                        IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                        interpreter.rangeError("skippedElements must not be negative")
                    }
                    if (number.isFinite() && number > 9007199254740991.0) {
                        IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                        interpreter.rangeError("skippedElements out of range")
                    }
                    toSkip = number
                }
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                var skipped = 0.0
                while (true) {
                    val value = IteratorBuiltins.nextValue(interpreter, record) ?: return@method JSBoolean.FALSE
                    if (skipped < toSkip) {
                        skipped += 1.0
                        continue
                    }
                    if (interpreter.conversions.sameValueZero(value, searchElement)) {
                        IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = false)
                        return@method JSBoolean.TRUE
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                JSBoolean.FALSE
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("join"),
            BuiltinHelpers.method(intrinsics, "join", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.join called on non-object")
                }
                val separatorValue = args.getOrElse(0) { JSUndefined }
                val separator = if (separatorValue is JSUndefined) {
                    ","
                } else {
                    try {
                        interpreter.toStringValue(separatorValue)
                    } catch (error: JSException) {
                        IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                        throw error
                    }
                }
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                val builder = StringBuilder()
                var first = true
                while (true) {
                    val value = IteratorBuiltins.nextValue(interpreter, record) ?: break
                    if (!first) builder.append(separator)
                    first = false
                    if (value !is JSNull && value !is JSUndefined) {
                        try {
                            builder.append(interpreter.toStringValue(value))
                        } catch (error: JSException) {
                            IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = true)
                            throw error
                        }
                    }
                }
                JSString(builder.toString())
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("map"),
            BuiltinHelpers.method(intrinsics, "map", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.map called on non-object")
                }
                val mapper = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(mapper)) {
                    IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.map requires a callable mapper")
                }
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                IteratorBuiltins.createHelper(interpreter, record) { _ ->
                    val value = IteratorBuiltins.nextValue(interpreter, record) ?: return@createHelper null
                    val index = counter
                    val mapped = try {
                        interpreter.callFunction(mapper, JSUndefined, listOf(value, JSNumber(index)))
                    } catch (error: JSException) {
                        IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = true)
                        throw error
                    }
                    counter += 1.0
                    mapped
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("filter"),
            BuiltinHelpers.method(intrinsics, "filter", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.filter called on non-object")
                }
                val predicate = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(predicate)) {
                    IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.filter requires a callable predicate")
                }
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                IteratorBuiltins.createHelper(interpreter, record) { _ ->
                    while (true) {
                        val value = IteratorBuiltins.nextValue(interpreter, record) ?: return@createHelper null
                        val index = counter
                        val keep = try {
                            interpreter.callFunction(
                                predicate,
                                JSUndefined,
                                listOf(value, JSNumber(index)),
                            )
                        } catch (error: JSException) {
                            IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = true)
                            throw error
                        }
                        counter += 1.0
                        if (interpreter.toBoolean(keep)) return@createHelper value
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("take"),
            BuiltinHelpers.method(intrinsics, "take", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.take called on non-object")
                }
                var remaining = IteratorBuiltins.toLimit(interpreter, args.getOrElse(0) { JSUndefined }, thisValue)
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                IteratorBuiltins.createHelper(interpreter, record) { _ ->
                    if (remaining <= 0.0) {
                        // 正常完成关闭底层迭代器：return 结果必须是对象
                        IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = false)
                        return@createHelper null
                    }
                    remaining -= 1.0
                    IteratorBuiltins.nextValue(interpreter, record)
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("drop"),
            BuiltinHelpers.method(intrinsics, "drop", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.drop called on non-object")
                }
                var remaining = IteratorBuiltins.toLimit(interpreter, args.getOrElse(0) { JSUndefined }, thisValue)
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                IteratorBuiltins.createHelper(interpreter, record) { _ ->
                    while (remaining > 0.0) {
                        remaining -= 1.0
                        if (IteratorBuiltins.nextValue(interpreter, record) == null) return@createHelper null
                    }
                    IteratorBuiltins.nextValue(interpreter, record)
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("flatMap"),
            BuiltinHelpers.method(intrinsics, "flatMap", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.flatMap called on non-object")
                }
                val mapper = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(mapper)) {
                    IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.flatMap requires a callable mapper")
                }
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                var inner: IteratorBuiltins.DirectIterator? = null
                IteratorBuiltins.createHelper(
                    interpreter,
                    record,
                    onReturn = { innerInterpreter ->
                        inner?.let { IteratorBuiltins.closeDirect(innerInterpreter, it, throwCompletion = false) }
                    },
                ) { _ ->
                    while (true) {
                        val currentInner = inner
                        if (currentInner != null) {
                            val innerValue = try {
                                IteratorBuiltins.nextValue(interpreter, currentInner)
                            } catch (error: JSException) {
                                // 内层 step 抛错：只关闭外层，内层保持原状
                                IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = true)
                                throw error
                            }
                            if (innerValue != null) return@createHelper innerValue
                            inner = null
                        }
                        val value = IteratorBuiltins.nextValue(interpreter, record) ?: return@createHelper null
                        val index = counter
                        val mapped = try {
                            interpreter.callFunction(
                                mapper,
                                JSUndefined,
                                listOf(value, JSNumber(index)),
                            )
                        } catch (error: JSException) {
                            IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = true)
                            throw error
                        }
                        counter += 1.0
                        val innerRecord = try {
                            IteratorBuiltins.getIteratorFlattenable(interpreter, mapped, rejectStrings = true)
                        } catch (error: JSException) {
                            IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = true)
                            throw error
                        }
                        inner = innerRecord
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("reduce"),
            BuiltinHelpers.method(intrinsics, "reduce", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.reduce called on non-object")
                }
                val reducer = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(reducer)) {
                    IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.reduce requires a callable reducer")
                }
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                val hasInitial = args.size > 1
                var accumulator: JSValue
                var counter: Double
                if (hasInitial) {
                    accumulator = args[1]
                    counter = 0.0
                } else {
                    accumulator = IteratorBuiltins.nextValue(interpreter, record)
                        ?: interpreter.typeError(
                            "Reduce of empty iterator with no initial value"
                        )
                    counter = 1.0
                }
                while (true) {
                    val value = IteratorBuiltins.nextValue(interpreter, record) ?: return@method accumulator
                    val index = counter
                    accumulator = try {
                        interpreter.callFunction(
                            reducer,
                            JSUndefined,
                            listOf(accumulator, value, JSNumber(index)),
                        )
                    } catch (error: JSException) {
                        IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = true)
                        throw error
                    }
                    counter += 1.0
                }
                @Suppress("UNREACHABLE_CODE")
                accumulator
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("toArray"),
            BuiltinHelpers.method(intrinsics, "toArray", 0) { interpreter, thisValue, _ ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.toArray called on non-object")
                }
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                val values = ArrayList<JSValue?>()
                while (true) {
                    val value = IteratorBuiltins.nextValue(interpreter, record) ?: break
                    values.add(value)
                }
                JSArray(values, prototype = intrinsics.arrayPrototype)
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("forEach"),
            BuiltinHelpers.method(intrinsics, "forEach", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.forEach called on non-object")
                }
                val callback = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(callback)) {
                    IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.forEach requires a callable callback")
                }
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                while (true) {
                    val value = IteratorBuiltins.nextValue(interpreter, record) ?: break
                    try {
                        interpreter.callFunction(
                            callback,
                            JSUndefined,
                            listOf(value, JSNumber(counter)),
                        )
                    } catch (error: JSException) {
                        IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = true)
                        throw error
                    }
                    counter += 1.0
                }
                JSUndefined
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("some"),
            BuiltinHelpers.method(intrinsics, "some", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.some called on non-object")
                }
                val predicate = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(predicate)) {
                    IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.some requires a callable predicate")
                }
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                while (true) {
                    val value = IteratorBuiltins.nextValue(interpreter, record) ?: return@method JSBoolean.FALSE
                    val matched = try {
                        interpreter.callFunction(
                            predicate,
                            JSUndefined,
                            listOf(value, JSNumber(counter)),
                        )
                    } catch (error: JSException) {
                        IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = true)
                        throw error
                    }
                    counter += 1.0
                    if (interpreter.toBoolean(matched)) {
                        IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = false)
                        return@method JSBoolean.TRUE
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                JSBoolean.FALSE
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("every"),
            BuiltinHelpers.method(intrinsics, "every", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.every called on non-object")
                }
                val predicate = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(predicate)) {
                    IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.every requires a callable predicate")
                }
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                while (true) {
                    val value = IteratorBuiltins.nextValue(interpreter, record) ?: return@method JSBoolean.TRUE
                    val matched = try {
                        interpreter.callFunction(
                            predicate,
                            JSUndefined,
                            listOf(value, JSNumber(counter)),
                        )
                    } catch (error: JSException) {
                        IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = true)
                        throw error
                    }
                    counter += 1.0
                    if (!interpreter.toBoolean(matched)) {
                        IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = false)
                        return@method JSBoolean.FALSE
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                JSBoolean.TRUE
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("find"),
            BuiltinHelpers.method(intrinsics, "find", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.find called on non-object")
                }
                val predicate = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(predicate)) {
                    IteratorBuiltins.closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.find requires a callable predicate")
                }
                val record = IteratorBuiltins.getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                while (true) {
                    val value = IteratorBuiltins.nextValue(interpreter, record) ?: return@method JSUndefined
                    val matched = try {
                        interpreter.callFunction(
                            predicate,
                            JSUndefined,
                            listOf(value, JSNumber(counter)),
                        )
                    } catch (error: JSException) {
                        IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = true)
                        throw error
                    }
                    counter += 1.0
                    if (interpreter.toBoolean(matched)) {
                        IteratorBuiltins.closeDirect(interpreter, record, throwCompletion = false)
                        return@method value
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                JSUndefined
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
    }
}
