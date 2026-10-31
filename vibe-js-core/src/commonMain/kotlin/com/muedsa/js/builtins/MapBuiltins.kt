package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSMap
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
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
                val adder = interpreter.getV(map, PropertyKey.Str("set"))
                if (!interpreter.isCallable(adder)) {
                    interpreter.typeError("'set' is not a function")
                }
                interpreter.consumeIterator(iterable) { entry ->
                    if (entry !is JSObject) {
                        interpreter.typeError("Iterator value is not an entry object")
                    }
                    val key = interpreter.getV(entry, PropertyKey.Str("0"))
                    val value = interpreter.getV(entry, PropertyKey.Str("1"))
                    interpreter.callFunction(adder, map, listOf(key, value))
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
        // Map[@@species]：返回 this
        mapConstructor.defineAccessorProperty(
            key = PropertyKey.Sym(intrinsics.symbolSpecies),
            get = BuiltinHelpers.method(intrinsics, "get [Symbol.species]", 0) { _, thisValue, _ ->
                thisValue
            },
            set = null,
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
            key = PropertyKey.Str("getOrInsert"),
            value = BuiltinHelpers.method(intrinsics, "getOrInsert", 2) { interpreter, thisValue, args ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.getOrInsert")
                val rawKey = args.getOrElse(0) { JSUndefined }
                val key = if (rawKey is JSNumber && rawKey.value == 0.0) JSNumber.ZERO else rawKey
                val value = args.getOrElse(1) { JSUndefined }
                val index = findEntryIndex(interpreter, map, key)
                if (index >= 0) {
                    map.entries[index].second
                } else {
                    setEntry(interpreter, map, key, value)
                    value
                }
            },
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("getOrInsertComputed"),
            value = BuiltinHelpers.method(intrinsics, "getOrInsertComputed", 2) { interpreter, thisValue, args ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.getOrInsertComputed")
                val rawKey = args.getOrElse(0) { JSUndefined }
                // 规范：先规范化键（-0 → +0），回调接收规范化后的键
                val key = if (rawKey is JSNumber && rawKey.value == 0.0) JSNumber.ZERO else rawKey
                val callback = args.getOrElse(1) { JSUndefined }
                if (!interpreter.isCallable(callback)) {
                    interpreter.typeError("Map.prototype.getOrInsertComputed requires a callable")
                }
                val index = findEntryIndex(interpreter, map, key)
                if (index >= 0) {
                    map.entries[index].second
                } else {
                    val computed = interpreter.callFunction(callback, JSUndefined, listOf(key))
                    setEntry(interpreter, map, key, computed)
                    computed
                }
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
            valueFor: (Pair<JSValue, JSValue>) -> JSValue,
        ) {
            val method = BuiltinHelpers.method(intrinsics, name, 0) { interpreter, thisValue, _ ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.$name")
                var index = 0
                IteratorBuiltins.createIteratorWithNext(intrinsics, intrinsics.mapIteratorPrototype) { _, _, _ ->
                    val result = JSObject(prototype = intrinsics.objectPrototype)
                    val done = index >= map.entries.size
                    val value = if (done) {
                        JSUndefined
                    } else {
                        val entry = map.entries[index]
                        index++
                        valueFor(entry)
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
            mapPrototype.defineDataProperty(PropertyKey.Str(name), method)
            if (name == "entries") {
                mapPrototype.defineDataProperty(PropertyKey.Sym(intrinsics.symbolIterator), method)
            }
        }

        iteratorMethod("keys") { entry -> entry.first }
        iteratorMethod("values") { entry -> entry.second }
        iteratorMethod("entries") { entry ->
            JSArray(
                mutableListOf<JSValue?>(entry.first, entry.second),
                prototype = intrinsics.arrayPrototype,
            )
        }

        // Map.groupBy(items, callback)：键按 SameValueZero 分组，组值为普通数组
        mapConstructor.defineDataProperty(
            key = PropertyKey.Str("groupBy"),
            value = BuiltinHelpers.method(intrinsics, "groupBy", 2) { interpreter, _, args ->
                val items = args.getOrElse(0) { JSUndefined }
                val callback = args.getOrElse(1) { JSUndefined }
                if (!interpreter.isCallable(callback)) {
                    interpreter.typeError("Map.groupBy requires a callable callback")
                }
                val result = JSMap(prototype = intrinsics.mapPrototype)
                var index = 0.0
                interpreter.consumeIterator(items) { value ->
                    val key = interpreter.callFunction(
                        callback,
                        JSUndefined,
                        listOf(value, JSNumber(index)),
                    )
                    index += 1.0
                    val existing = result.entries.firstOrNull {
                        interpreter.conversions.sameValueZero(it.first, key)
                    }
                    val group = existing?.second as? JSArray
                    if (group == null) {
                        val created = JSArray(prototype = intrinsics.arrayPrototype)
                        created.setElement(0, value)
                        result.entries.add(key to created)
                    } else {
                        group.setElement(group.lengthLong, value)
                    }
                }
                result
            },
        )

        registry.defineGlobal("Map", mapConstructor)
    }

    private fun requireMap(interpreter: Interpreter, thisValue: JSValue, name: String): JSMap {
        if (thisValue is com.muedsa.js.runtime.value.JSWeakMap) {
            interpreter.typeError("$name requires that 'this' be a Map")
        }
        return thisValue as? JSMap ?: interpreter.typeError("$name requires that 'this' be a Map")
    }

    private fun findEntryIndex(interpreter: Interpreter, map: JSMap, key: JSValue): Int =
        map.entries.indexOfFirst { interpreter.conversions.sameValueZero(it.first, key) }

    private fun setEntry(interpreter: Interpreter, map: JSMap, key: JSValue, value: JSValue) {
        // 规范：Map 中的 -0 键归一化为 +0
        val normalizedKey = if (key is JSNumber && key.value == 0.0) JSNumber.ZERO else key
        val index = findEntryIndex(interpreter, map, normalizedKey)
        if (index >= 0) {
            map.entries[index] = normalizedKey to value
        } else {
            map.entries.add(normalizedKey to value)
        }
    }
}
