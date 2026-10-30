package com.muedsa.js.builtins

import com.muedsa.js.runtime.Intrinsics
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * 迭代器辅助：构造符合迭代器协议的 JS 对象。
 */
internal object IteratorBuiltins {

    /**
     * 为给定值列表创建迭代器对象。
     *
     * @param toStringTag 可选的 Symbol.toStringTag
     */
    fun createIterator(
        intrinsics: Intrinsics,
        values: List<JSValue>,
        toStringTag: String? = null,
    ): JSObject {
        var index = 0
        val iterator = JSObject(prototype = intrinsics.objectPrototype)

        val next = BuiltinHelpers.method(intrinsics, "next", 0) { _, _, _ ->
            val result = JSObject(prototype = intrinsics.objectPrototype)
            if (index >= values.size) {
                result.defineDataProperty(
                    key = PropertyKey.Str("value"),
                    value = JSUndefined,
                    writable = true,
                    enumerable = true,
                    configurable = true,
                )
                result.defineDataProperty(
                    key = PropertyKey.Str("done"),
                    value = JSBoolean.TRUE,
                    writable = true,
                    enumerable = true,
                    configurable = true,
                )
            } else {
                result.defineDataProperty(
                    key = PropertyKey.Str("value"),
                    value = values[index],
                    writable = true,
                    enumerable = true,
                    configurable = true,
                )
                index++
                result.defineDataProperty(
                    key = PropertyKey.Str("done"),
                    value = JSBoolean.FALSE,
                    writable = true,
                    enumerable = true,
                    configurable = true,
                )
            }
            result
        }

        iterator.defineDataProperty(
            key = PropertyKey.Str("next"),
            value = next,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        iterator.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolIterator),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.iterator]", 0) { _, thisValue, _ ->
                thisValue
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        if (toStringTag != null) {
            iterator.defineDataProperty(
                key = PropertyKey.Sym(intrinsics.symbolToStringTag),
                value = JSString(toStringTag),
                writable = false,
                enumerable = false,
                configurable = true,
            )
        }
        return iterator
    }
}
