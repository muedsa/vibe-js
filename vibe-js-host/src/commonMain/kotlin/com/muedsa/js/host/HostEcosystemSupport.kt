package com.muedsa.js.host

import com.muedsa.js.builtins.BuiltinHelpers
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.Intrinsics
import com.muedsa.js.runtime.value.JSArrayBuffer
import com.muedsa.js.runtime.value.JSDataView
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSTypedArray
import com.muedsa.js.runtime.value.JSTypedArrayType
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * 宿主内建实现的共享辅助。
 *
 * 这些函数只操作核心公开的值模型与 Interpreter API，不引入平台 API；
 * 二进制数据统一通过 Uint8Array 视图读写，避免宿主直接触碰 ArrayBuffer 内部字节。
 */

/** 在对象上定义一个不可枚举的原生方法。 */
internal fun JSObject.defineHostMethod(
    intrinsics: Intrinsics,
    name: String,
    length: Int = 0,
    body: suspend (Interpreter, JSValue, List<JSValue>) -> JSValue,
) {
    defineDataProperty(
        key = PropertyKey.Str(name),
        value = BuiltinHelpers.method(intrinsics, name, length, body),
        writable = true,
        enumerable = false,
        configurable = true,
    )
}

/** 在对象上定义一个不可枚举的原生 getter。 */
internal fun JSObject.defineHostGetter(
    intrinsics: Intrinsics,
    name: String,
    get: suspend (Interpreter, JSValue, List<JSValue>) -> JSValue,
) {
    defineAccessorProperty(
        key = PropertyKey.Str(name),
        get = BuiltinHelpers.method(intrinsics, "get $name", 0, get),
        set = null,
        enumerable = false,
        configurable = true,
    )
}

/** 在对象上定义一个不可枚举的原生 getter/setter 访问器。 */
internal fun JSObject.defineHostAccessor(
    intrinsics: Intrinsics,
    name: String,
    get: suspend (Interpreter, JSValue, List<JSValue>) -> JSValue,
    set: suspend (Interpreter, JSValue, List<JSValue>) -> JSValue,
) {
    defineAccessorProperty(
        key = PropertyKey.Str(name),
        get = BuiltinHelpers.method(intrinsics, "get $name", 0, get),
        set = BuiltinHelpers.method(intrinsics, "set $name", 1, set),
        enumerable = false,
        configurable = true,
    )
}

/** 在对象上定义一个不可枚举的原生 setter。 */
internal fun JSObject.defineHostSetter(
    intrinsics: Intrinsics,
    name: String,
    set: suspend (Interpreter, JSValue, List<JSValue>) -> JSValue,
) {
    defineAccessorProperty(
        key = PropertyKey.Str(name),
        get = null,
        set = BuiltinHelpers.method(intrinsics, "set $name", 1, set),
        enumerable = false,
        configurable = true,
    )
}

/** 设置 `Symbol.toStringTag`。 */
internal fun JSObject.defineHostToStringTag(intrinsics: Intrinsics, tag: String) {
    defineDataProperty(
        key = PropertyKey.Sym(intrinsics.symbolToStringTag),
        value = JSString(tag),
        writable = false,
        enumerable = false,
        configurable = true,
    )
}

/**
 * 创建 Uint8Array 并逐字节写入。
 *
 * 使用 Interpreter 的公开 TypedArray 写入路径，避免宿主依赖核心内部字节访问器。
 */
internal suspend fun createUint8Array(
    interpreter: Interpreter,
    intrinsics: Intrinsics,
    bytes: ByteArray,
): JSTypedArray {
    val buffer = JSArrayBuffer(
        prototype = intrinsics.arrayBufferPrototype,
        byteLength = bytes.size,
    )
    val array = JSTypedArray(
        prototype = intrinsics.typedArrayPrototypes.getValue(JSTypedArrayType.UINT8),
        type = JSTypedArrayType.UINT8,
        buffer = buffer,
        byteOffset = 0,
        length = bytes.size,
    )
    for (index in bytes.indices) {
        interpreter.setTypedArrayElement(
            array,
            index,
            JSNumber((bytes[index].toInt() and 0xFF).toDouble()),
        )
    }
    return array
}

/** 从 ArrayBuffer 的一段窗口读取字节；调用方保证窗口合法。 */
internal suspend fun readBufferBytes(
    interpreter: Interpreter,
    intrinsics: Intrinsics,
    buffer: JSArrayBuffer,
    byteOffset: Int,
    length: Int,
): ByteArray {
    if (buffer.isDetached) {
        interpreter.typeError("Cannot read a detached ArrayBuffer")
    }
    if (length == 0) return ByteArray(0)
    val view = JSTypedArray(
        prototype = intrinsics.typedArrayPrototypes.getValue(JSTypedArrayType.UINT8),
        type = JSTypedArrayType.UINT8,
        buffer = buffer,
        byteOffset = byteOffset,
        length = length,
    )
    val result = ByteArray(length)
    for (index in 0 until length) {
        val value = interpreter.getV(view, PropertyKey.Str(index.toString()))
        result[index] = ((value as? JSNumber)?.value?.toInt() ?: 0).toByte()
    }
    return result
}

/**
 * 读取 ArrayBuffer / TypedArray / DataView 的字节。
 *
 * 不是 ArrayBuffer 视图时返回 null，由调用方按各自 API 决定抛 TypeError。
 */
internal suspend fun readArrayBufferLikeBytes(
    interpreter: Interpreter,
    intrinsics: Intrinsics,
    value: JSValue,
): ByteArray? = when (value) {
    is JSArrayBuffer -> readBufferBytes(interpreter, intrinsics, value, 0, value.byteLength)
    is JSTypedArray -> readBufferBytes(
        interpreter,
        intrinsics,
        value.buffer,
        value.byteOffset,
        value.byteLength,
    )

    is JSDataView -> readBufferBytes(
        interpreter,
        intrinsics,
        value.buffer,
        value.byteOffset,
        value.byteLength,
    )

    else -> null
}
