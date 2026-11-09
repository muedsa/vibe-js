package com.muedsa.js.host

import com.muedsa.js.builtins.BuiltinHelpers
import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.Intrinsics
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.regex.JsRegexEngine
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSArrayBuffer
import com.muedsa.js.runtime.value.JSBigInt
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSDataView
import com.muedsa.js.runtime.value.JSDate
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSGenerator
import com.muedsa.js.runtime.value.JSMap
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSProxy
import com.muedsa.js.runtime.value.JSRegExp
import com.muedsa.js.runtime.value.JSSet
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSSymbol
import com.muedsa.js.runtime.value.JSTypedArray
import com.muedsa.js.runtime.value.JSTypedArrayType
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.JSWeakMap
import com.muedsa.js.runtime.value.JSWeakSet
import com.muedsa.js.runtime.value.PropertyKey

/**
 * `structuredClone` 的宿主子集。
 *
 * 支持：
 * - 原始值（Symbol 除外）、普通对象、数组（含空洞/长度）；
 * - Date、RegExp、Map、Set；
 * - ArrayBuffer、TypedArray、DataView（共享底层缓冲区的视图会克隆为共享新缓冲区）；
 * - 循环引用与重复引用。
 *
 * 不支持：函数、Symbol、Proxy、Promise、Generator、WeakMap/WeakSet、Error 子类，
 * 以及 transfer 列表；遇到这些值抛 DataCloneError。普通对象/数组只复制可枚举
 * 自有字符串属性，访问器属性按值读取。
 */
internal fun installStructuredClone(registry: BuiltinRegistry) {
    val intrinsics = registry.intrinsics
    registry.defineGlobal(
        name = "structuredClone",
        value = BuiltinHelpers.method(intrinsics, "structuredClone", 1) { interpreter, _, args ->
            val value = args.getOrElse(0) { JSUndefined }
            val options = args.getOrNull(1)
            if (options is JSObject) {
                val transfer = interpreter.getV(options, PropertyKey.Str("transfer"))
                if (transfer !is JSUndefined) {
                    throw dataCloneError(intrinsics, "structuredClone transfer lists are not supported")
                }
            }
            StructuredCloner(interpreter, intrinsics).clone(value)
        },
        writable = true,
        enumerable = false,
        configurable = true,
    )
}

private fun dataCloneError(intrinsics: Intrinsics, message: String): JSException =
    JSException(JSError("DataCloneError", message, intrinsics.errorPrototype))

private class StructuredCloner(
    private val interpreter: Interpreter,
    private val intrinsics: Intrinsics,
) {
    /** 已克隆对象；JSObject 默认按引用身份比较，正好用作身份表。 */
    private val clonedObjects = LinkedHashMap<JSObject, JSValue>()

    /** 底层 ArrayBuffer 身份表；多个视图共享同一缓冲区时必须共享克隆。 */
    private val clonedBuffers = LinkedHashMap<JSArrayBuffer, JSArrayBuffer>()

    suspend fun clone(value: JSValue): JSValue = when (value) {
        is JSUndefined, is JSNull, is JSBoolean, is JSNumber, is JSString, is JSBigInt -> value
        is JSSymbol -> throw dataCloneError(intrinsics, "Symbol cannot be cloned")
        is JSFunction, is JSNativeFunction -> throw dataCloneError(intrinsics, "Function cannot be cloned")
        is JSProxy -> throw dataCloneError(intrinsics, "Proxy cannot be cloned")
        is JSPromise -> throw dataCloneError(intrinsics, "Promise cannot be cloned")
        is JSGenerator -> throw dataCloneError(intrinsics, "Generator cannot be cloned")
        is JSWeakMap, is JSWeakSet -> throw dataCloneError(intrinsics, "WeakMap/WeakSet cannot be cloned")
        is JSDate -> cloneDate(value)
        is JSRegExp -> cloneRegExp(value)
        is JSArrayBuffer -> cloneArrayBuffer(value)
        is JSTypedArray -> cloneTypedArray(value)
        is JSDataView -> cloneDataView(value)
        is JSMap -> cloneMap(value)
        is JSSet -> cloneSet(value)
        is JSArray -> cloneArray(value)
        is JSObject -> cloneOrdinaryObject(value)
    }

    private suspend fun cloneDate(source: JSDate): JSDate {
        clonedObjects[source]?.let { return it as JSDate }
        val target = JSDate(source.timeValue, intrinsics.datePrototype)
        clonedObjects[source] = target
        return target
    }

    private suspend fun cloneRegExp(source: JSRegExp): JSRegExp {
        clonedObjects[source]?.let { return it as JSRegExp }
        val target = JSRegExp(
            pattern = source.pattern,
            flags = source.flags,
            matcher = JsRegexEngine(source.pattern, source.flags),
            prototype = intrinsics.regexpPrototype,
        )
        target.setLastIndex(source.getLastIndex())
        clonedObjects[source] = target
        return target
    }

    private suspend fun cloneArrayBuffer(source: JSArrayBuffer): JSArrayBuffer {
        clonedBuffers[source]?.let { return it }
        if (source.isDetached) {
            throw dataCloneError(intrinsics, "Cannot clone a detached ArrayBuffer")
        }
        val target = JSArrayBuffer(
            prototype = intrinsics.arrayBufferPrototype,
            byteLength = source.byteLength,
        )
        val bytes = readBufferBytes(interpreter, intrinsics, source, 0, source.byteLength)
        writeBufferBytes(target, bytes)
        clonedBuffers[source] = target
        return target
    }

    private suspend fun cloneTypedArray(source: JSTypedArray): JSTypedArray {
        clonedObjects[source]?.let { return it as JSTypedArray }
        if (source.buffer.isDetached) {
            throw dataCloneError(intrinsics, "Cannot clone a view over a detached ArrayBuffer")
        }
        val target = JSTypedArray(
            prototype = intrinsics.typedArrayPrototypes.getValue(source.type),
            type = source.type,
            buffer = cloneArrayBuffer(source.buffer),
            byteOffset = source.byteOffset,
            length = source.length,
        )
        clonedObjects[source] = target
        return target
    }

    private suspend fun cloneDataView(source: JSDataView): JSDataView {
        clonedObjects[source]?.let { return it as JSDataView }
        if (source.buffer.isDetached) {
            throw dataCloneError(intrinsics, "Cannot clone a view over a detached ArrayBuffer")
        }
        val target = JSDataView(
            prototype = intrinsics.dataViewPrototype,
            buffer = cloneArrayBuffer(source.buffer),
            byteOffset = source.byteOffset,
            byteLength = source.byteLength,
        )
        clonedObjects[source] = target
        return target
    }

    private suspend fun cloneMap(source: JSMap): JSMap {
        clonedObjects[source]?.let { return it as JSMap }
        val target = JSMap(intrinsics.mapPrototype)
        clonedObjects[source] = target
        for ((key, value) in source.liveEntries()) {
            target.setEntry(clone(key), clone(value))
        }
        return target
    }

    private suspend fun cloneSet(source: JSSet): JSSet {
        clonedObjects[source]?.let { return it as JSSet }
        val target = JSSet(intrinsics.setPrototype)
        clonedObjects[source] = target
        for (value in source.liveValues()) {
            target.addValue(clone(value))
        }
        return target
    }

    private suspend fun cloneArray(source: JSArray): JSArray {
        clonedObjects[source]?.let { return it as JSArray }
        val target = JSArray(prototype = intrinsics.arrayPrototype)
        clonedObjects[source] = target
        copyEnumerableProperties(source, target)
        target.setLength(source.lengthLong)
        return target
    }

    private suspend fun cloneOrdinaryObject(source: JSObject): JSObject {
        clonedObjects[source]?.let { return it as JSObject }
        val target = JSObject(
            prototype = if (source.prototype == null) null else intrinsics.objectPrototype,
        )
        clonedObjects[source] = target
        copyEnumerableProperties(source, target)
        return target
    }

    private suspend fun copyEnumerableProperties(source: JSObject, target: JSObject) {
        for (key in source.ownPropertyKeys()) {
            if (key !is PropertyKey.Str) continue
            if (key == PropertyKey.LENGTH) continue
            val descriptor = source.getOwnProperty(key) ?: continue
            if (!descriptor.enumerable) continue
            val value = interpreter.getV(source, key)
            target.defineDataProperty(
                key = key,
                value = clone(value),
                writable = true,
                enumerable = true,
                configurable = true,
            )
        }
    }

    private suspend fun writeBufferBytes(target: JSArrayBuffer, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val view = JSTypedArray(
            prototype = intrinsics.typedArrayPrototypes.getValue(JSTypedArrayType.UINT8),
            type = JSTypedArrayType.UINT8,
            buffer = target,
            byteOffset = 0,
            length = bytes.size,
        )
        for (index in bytes.indices) {
            interpreter.setTypedArrayElement(
                view,
                index,
                JSNumber((bytes[index].toInt() and 0xFF).toDouble()),
            )
        }
    }
}
