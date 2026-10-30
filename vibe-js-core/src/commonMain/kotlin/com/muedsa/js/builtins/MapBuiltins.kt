package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSMap
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Map 构造器与 Map.prototype。
 */
object MapBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val mapPrototype = intrinsics.mapPrototype

        val mapConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "Map",
            length = 0,
            prototypeObject = mapPrototype,
        ) { interpreter, _, args ->
            if (!interpreter.nativeConstructCall) {
                interpreter.typeError("Constructor Map requires 'new'")
            }
            val map = JSMap(prototype = mapPrototype)
            val iterable = args.getOrNull(0)
            if (iterable != null && iterable !is JSUndefined && iterable !is JSNull) {
                for (entry in interpreter.iterateValues(iterable)) {
                    val pair = interpreter.iterateValues(entry)
                    if (pair.size < 2) {
                        interpreter.typeError("Iterator value is not an entry object")
                    }
                    setEntry(interpreter, map, pair[0], pair[1])
                }
            }
            map
        }

        mapPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = mapConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = com.muedsa.js.runtime.value.JSString("Map"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        mapPrototype.defineAccessorProperty(
            key = PropertyKey.Str("size"),
            get = BuiltinHelpers.method(intrinsics, "get size", 0) { interpreter, thisValue, _ ->
                com.muedsa.js.runtime.value.JSNumber(
                    requireMap(interpreter, thisValue, "Map.prototype.size").entries.size.toDouble()
                )
            },
            set = null,
            enumerable = false,
            configurable = true,
        )

        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("set"),
            value = BuiltinHelpers.method(intrinsics, "set", 2) { interpreter, thisValue, args ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.set")
                val key = args.getOrElse(0) { JSUndefined }
                val value = args.getOrElse(1) { JSUndefined }
                setEntry(interpreter, map, key, value)
                map
            },
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("get"),
            value = BuiltinHelpers.method(intrinsics, "get", 1) { interpreter, thisValue, args ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.get")
                val index = findEntryIndex(interpreter, map, args.getOrElse(0) { JSUndefined })
                if (index < 0) JSUndefined else map.entries[index].second
            },
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("has"),
            value = BuiltinHelpers.method(intrinsics, "has", 1) { interpreter, thisValue, args ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.has")
                JSBoolean.getJsBoolean(
                    findEntryIndex(interpreter, map, args.getOrElse(0) { JSUndefined }) >= 0
                )
            },
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("delete"),
            value = BuiltinHelpers.method(intrinsics, "delete", 1) { interpreter, thisValue, args ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.delete")
                val index = findEntryIndex(interpreter, map, args.getOrElse(0) { JSUndefined })
                if (index < 0) {
                    JSBoolean.FALSE
                } else {
                    map.entries.removeAt(index)
                    JSBoolean.TRUE
                }
            },
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("clear"),
            value = BuiltinHelpers.method(intrinsics, "clear", 0) { interpreter, thisValue, _ ->
                requireMap(interpreter, thisValue, "Map.prototype.clear").entries.clear()
                JSUndefined
            },
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("forEach"),
            value = BuiltinHelpers.method(intrinsics, "forEach", 1) { interpreter, thisValue, args ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.forEach")
                val callback = args.getOrNull(0) ?: JSUndefined
                if (!interpreter.isCallable(callback)) {
                    interpreter.typeError("Map.prototype.forEach requires a function")
                }
                val thisArg = args.getOrNull(1) ?: JSUndefined
                val snapshot = map.entries.toList()
                for ((key, value) in snapshot) {
                    interpreter.callFunction(callback, thisArg, listOf(value, key, map))
                }
                JSUndefined
            },
        )

        fun iteratorMethod(
            name: String,
            values: (JSMap) -> List<JSValue>,
        ) {
            val method = BuiltinHelpers.method(intrinsics, name, 0) { interpreter, thisValue, _ ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.$name")
                IteratorBuiltins.createIterator(intrinsics, values(map), "Map Iterator")
            }
            mapPrototype.defineDataProperty(PropertyKey.Str(name), method)
            if (name == "entries") {
                mapPrototype.defineDataProperty(PropertyKey.Sym(intrinsics.symbolIterator), method)
            }
        }

        iteratorMethod("keys") { map -> map.entries.map { it.first } }
        iteratorMethod("values") { map -> map.entries.map { it.second } }
        iteratorMethod("entries") { map ->
            map.entries.map { (key, value) ->
                JSArray(
                    mutableListOf<JSValue?>(key, value),
                    prototype = intrinsics.arrayPrototype,
                )
            }
        }

        registry.defineGlobal("Map", mapConstructor)
    }

    private fun requireMap(interpreter: Interpreter, thisValue: JSValue, name: String): JSMap =
        thisValue as? JSMap ?: interpreter.typeError("$name requires that 'this' be a Map")

    private fun findEntryIndex(interpreter: Interpreter, map: JSMap, key: JSValue): Int =
        map.entries.indexOfFirst { interpreter.conversions.sameValueZero(it.first, key) }

    private fun setEntry(interpreter: Interpreter, map: JSMap, key: JSValue, value: JSValue) {
        val index = findEntryIndex(interpreter, map, key)
        if (index >= 0) {
            map.entries[index] = key to value
        } else {
            map.entries.add(key to value)
        }
    }
}
