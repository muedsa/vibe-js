package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSSet
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Set 构造器与 Set.prototype。
 */
object SetBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val setPrototype = intrinsics.setPrototype

        val setConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "Set",
            length = 0,
            prototypeObject = setPrototype,
        ) { interpreter, _, args ->
            if (!interpreter.nativeConstructCall) {
                interpreter.typeError("Constructor Set requires 'new'")
            }
            val set = JSSet(prototype = setPrototype)
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

        setPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = setConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        // Set[@@species]：返回 this
        setConstructor.defineAccessorProperty(
            key = PropertyKey.Sym(intrinsics.symbolSpecies),
            get = BuiltinHelpers.method(intrinsics, "get [Symbol.species]", 0) { _, thisValue, _ ->
                thisValue
            },
            set = null,
            enumerable = false,
            configurable = true,
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("Set"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        setPrototype.defineAccessorProperty(
            key = PropertyKey.Str("size"),
            get = BuiltinHelpers.method(intrinsics, "get size", 0) { interpreter, thisValue, _ ->
                JSNumber(requireSet(interpreter, thisValue, "Set.prototype.size").values.size.toDouble())
            },
            set = null,
            enumerable = false,
            configurable = true,
        )

        setPrototype.defineDataProperty(
            key = PropertyKey.Str("add"),
            value = BuiltinHelpers.method(intrinsics, "add", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.add")
                addValue(interpreter, set, args.getOrElse(0) { JSUndefined })
                set
            },
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("has"),
            value = BuiltinHelpers.method(intrinsics, "has", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.has")
                JSBoolean.getJsBoolean(
                    set.values.any {
                        interpreter.conversions.sameValueZero(it, args.getOrElse(0) { JSUndefined })
                    }
                )
            },
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("delete"),
            value = BuiltinHelpers.method(intrinsics, "delete", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.delete")
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
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("clear"),
            value = BuiltinHelpers.method(intrinsics, "clear", 0) { interpreter, thisValue, _ ->
                requireSet(interpreter, thisValue, "Set.prototype.clear").values.clear()
                JSUndefined
            },
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("forEach"),
            value = BuiltinHelpers.method(intrinsics, "forEach", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.forEach")
                val callback = args.getOrNull(0) ?: JSUndefined
                if (!interpreter.isCallable(callback)) {
                    interpreter.typeError("Set.prototype.forEach requires a function")
                }
                val thisArg = args.getOrNull(1) ?: JSUndefined
                val snapshot = set.values.toList()
                for (value in snapshot) {
                    interpreter.callFunction(callback, thisArg, listOf(value, value, set))
                }
                JSUndefined
            },
        )

        fun iteratorMethod(
            name: String,
            valueFor: (JSValue) -> JSValue,
        ) {
            val method = BuiltinHelpers.method(intrinsics, name, 0) { interpreter, thisValue, _ ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.$name")
                var index = 0
                IteratorBuiltins.createIteratorWithNext(intrinsics, "Set Iterator") { _, _, _ ->
                    val result = JSObject(prototype = intrinsics.objectPrototype)
                    val done = index >= set.values.size
                    val value = if (done) {
                        JSUndefined
                    } else {
                        val element = set.values[index]
                        index++
                        valueFor(element)
                    }
                    result.defineDataProperty(
                        PropertyKey.Str("value"), value, true, true, true,
                    )
                    result.defineDataProperty(
                        PropertyKey.Str("done"), JSBoolean.getJsBoolean(done), true, true, true,
                    )
                    result
                }
            }
            setPrototype.defineDataProperty(PropertyKey.Str(name), method)
            if (name == "values") {
                setPrototype.defineDataProperty(PropertyKey.Sym(intrinsics.symbolIterator), method)
            }
        }

        iteratorMethod("values") { it }
        iteratorMethod("keys") { it }
        iteratorMethod("entries") { value ->
            JSArray(
                mutableListOf<JSValue?>(value, value),
                prototype = intrinsics.arrayPrototype,
            )
        }

        registry.defineGlobal("Set", setConstructor)
    }

    private fun requireSet(interpreter: Interpreter, thisValue: JSValue, name: String): JSSet {
        if (thisValue is com.muedsa.js.runtime.value.JSWeakSet) {
            interpreter.typeError("$name requires that 'this' be a Set")
        }
        return thisValue as? JSSet ?: interpreter.typeError("$name requires that 'this' be a Set")
    }

    private fun addValue(interpreter: Interpreter, set: JSSet, value: JSValue) {
        if (set.values.none { interpreter.conversions.sameValueZero(it, value) }) {
            set.values.add(value)
        }
    }
}
