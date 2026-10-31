package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSSet
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSWeakSet
import com.muedsa.js.runtime.value.PropertyKey

/**
 * WeakSet 构造器（基于 Set 存储；不提供 size 与迭代）。
 */
object WeakSetBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val prototype = intrinsics.weakSetPrototype

        val constructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "WeakSet",
            length = 0,
            prototypeObject = prototype,
        ) { interpreter, _, args ->
            if (!interpreter.nativeConstructCall) {
                interpreter.typeError("Constructor WeakSet requires 'new'")
            }
            val set = JSWeakSet(prototype = prototype)
            val iterable = args.getOrNull(0)
            if (iterable != null && iterable !is JSUndefined && iterable !is JSNull) {
                val adder = interpreter.getV(set, PropertyKey.Str("add"))
                if (!interpreter.isCallable(adder)) {
                    interpreter.typeError("'add' is not a function")
                }
                interpreter.consumeIterator(iterable) { value ->
                    interpreter.callFunction(adder, set, listOf(value))
                }
            }
            set
        }

        prototype.defineDataProperty(PropertyKey.CONSTRUCTOR, constructor, true, false, true)
        prototype.defineDataProperty(
            PropertyKey.Sym(intrinsics.symbolToStringTag),
            JSString("WeakSet"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        prototype.defineDataProperty(
            PropertyKey.Str("add"),
            value = BuiltinHelpers.method(intrinsics, "add", 1) { interpreter, thisValue, args ->
                val set = thisValue as? JSWeakSet
                    ?: interpreter.typeError("WeakSet.prototype.add requires that 'this' be a WeakSet")
                val value = args.getOrElse(0) { JSUndefined }
                if (!WeakMapBuiltins.canBeHeldWeakly(value)) {
                    interpreter.typeError("Invalid value used in WeakSet")
                }
                if (set.values.none { interpreter.conversions.sameValueZero(it, value) }) {
                    set.values.add(value)
                }
                set
            },
        )
        prototype.defineDataProperty(
            PropertyKey.Str("has"),
            value = BuiltinHelpers.method(intrinsics, "has", 1) { interpreter, thisValue, args ->
                val set = thisValue as? JSWeakSet
                    ?: interpreter.typeError("WeakSet.prototype.has requires that 'this' be a WeakSet")
                JSBoolean.getJsBoolean(
                    set.values.any {
                        interpreter.conversions.sameValueZero(it, args.getOrElse(0) { JSUndefined })
                    }
                )
            },
        )
        prototype.defineDataProperty(
            PropertyKey.Str("delete"),
            value = BuiltinHelpers.method(intrinsics, "delete", 1) { interpreter, thisValue, args ->
                val set = thisValue as? JSWeakSet
                    ?: interpreter.typeError("WeakSet.prototype.delete requires that 'this' be a WeakSet")
                val index = set.values.indexOfFirst {
                    interpreter.conversions.sameValueZero(it, args.getOrElse(0) { JSUndefined })
                }
                if (index < 0) {
                    JSBoolean.FALSE
                } else {
                    set.values.removeAt(index)
                    JSBoolean.TRUE
                }
            },
        )

        registry.defineGlobal("WeakSet", constructor)
    }
}
