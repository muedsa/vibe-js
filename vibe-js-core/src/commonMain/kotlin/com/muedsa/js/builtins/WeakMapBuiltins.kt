package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.value.JSMap
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSWeakMap
import com.muedsa.js.runtime.value.PropertyKey

/**
 * WeakMap 构造器（基于 Map 存储；不提供 size 与迭代）。
 */
object WeakMapBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val prototype = intrinsics.weakMapPrototype

        val constructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "WeakMap",
            length = 0,
            prototypeObject = prototype,
        ) { interpreter, _, args ->
            if (!interpreter.nativeConstructCall) {
                interpreter.typeError("Constructor WeakMap requires 'new'")
            }
            val map = JSWeakMap(prototype = prototype)
            val iterable = args.getOrNull(0)
            if (iterable != null && iterable !is JSUndefined && iterable !is JSNull) {
                for (entry in interpreter.iterateValues(iterable)) {
                    val pair = interpreter.iterateValues(entry)
                    if (pair.size >= 2) {
                        if (pair[0] !is JSObject) {
                            interpreter.typeError("Invalid value used as WeakMap key")
                        }
                        val index = map.entries.indexOfFirst {
                            interpreter.conversions.sameValueZero(it.first, pair[0])
                        }
                        if (index >= 0) map.entries[index] = pair[0] to pair[1]
                        else map.entries.add(pair[0] to pair[1])
                    }
                }
            }
            map
        }

        prototype.defineDataProperty(PropertyKey.CONSTRUCTOR, constructor, true, false, true)
        prototype.defineDataProperty(
            PropertyKey.Sym(intrinsics.symbolToStringTag),
            JSString("WeakMap"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        prototype.defineDataProperty(
            PropertyKey.Str("set"),
            value = BuiltinHelpers.method(intrinsics, "set", 2) { interpreter, thisValue, args ->
                val map = thisValue as? JSMap
                    ?: interpreter.typeError("WeakMap.prototype.set requires that 'this' be a WeakMap")
                val key = args.getOrElse(0) { JSUndefined }
                val value = args.getOrElse(1) { JSUndefined }
                if (key !is JSObject) {
                    interpreter.typeError("Invalid value used as WeakMap key")
                }
                val index = map.entries.indexOfFirst {
                    interpreter.conversions.sameValueZero(it.first, key)
                }
                if (index >= 0) map.entries[index] = key to value else map.entries.add(key to value)
                map
            },
        )
        prototype.defineDataProperty(
            PropertyKey.Str("get"),
            value = BuiltinHelpers.method(intrinsics, "get", 1) { interpreter, thisValue, args ->
                val map = thisValue as? JSMap
                    ?: interpreter.typeError("WeakMap.prototype.get requires that 'this' be a WeakMap")
                val index = map.entries.indexOfFirst {
                    interpreter.conversions.sameValueZero(it.first, args.getOrElse(0) { JSUndefined })
                }
                if (index < 0) JSUndefined else map.entries[index].second
            },
        )
        prototype.defineDataProperty(
            PropertyKey.Str("has"),
            value = BuiltinHelpers.method(intrinsics, "has", 1) { interpreter, thisValue, args ->
                val map = thisValue as? JSMap
                    ?: interpreter.typeError("WeakMap.prototype.has requires that 'this' be a WeakMap")
                com.muedsa.js.runtime.value.JSBoolean.getJsBoolean(
                    map.entries.any {
                        interpreter.conversions.sameValueZero(it.first, args.getOrElse(0) { JSUndefined })
                    }
                )
            },
        )
        prototype.defineDataProperty(
            PropertyKey.Str("delete"),
            value = BuiltinHelpers.method(intrinsics, "delete", 1) { interpreter, thisValue, args ->
                val map = thisValue as? JSMap
                    ?: interpreter.typeError("WeakMap.prototype.delete requires that 'this' be a WeakMap")
                val index = map.entries.indexOfFirst {
                    interpreter.conversions.sameValueZero(it.first, args.getOrElse(0) { JSUndefined })
                }
                if (index < 0) {
                    com.muedsa.js.runtime.value.JSBoolean.FALSE
                } else {
                    map.entries.removeAt(index)
                    com.muedsa.js.runtime.value.JSBoolean.TRUE
                }
            },
        )

        registry.defineGlobal("WeakMap", constructor)
    }
}
