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
                    requireMap(interpreter, thisValue, "Map.prototype.size").liveSize.toDouble()
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
                // -0 键归一化为 +0 由 JSMap.setEntry 统一处理
                map.setEntry(
                    args.getOrElse(0) { JSUndefined },
                    args.getOrElse(1) { JSUndefined },
                )
                map
            },
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("get"),
            value = BuiltinHelpers.method(intrinsics, "get", 1) { interpreter, thisValue, args ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.get")
                map.getEntry(args.getOrElse(0) { JSUndefined })?.second ?: JSUndefined
            },
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("has"),
            value = BuiltinHelpers.method(intrinsics, "has", 1) { interpreter, thisValue, args ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.has")
                JSBoolean.getJsBoolean(map.containsKey(args.getOrElse(0) { JSUndefined }))
            },
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("delete"),
            value = BuiltinHelpers.method(intrinsics, "delete", 1) { interpreter, thisValue, args ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.delete")
                JSBoolean.getJsBoolean(map.deleteEntry(args.getOrElse(0) { JSUndefined }))
            },
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("clear"),
            value = BuiltinHelpers.method(intrinsics, "clear", 0) { interpreter, thisValue, _ ->
                requireMap(interpreter, thisValue, "Map.prototype.clear").clearEntries()
                JSUndefined
            },
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("getOrInsert"),
            value = BuiltinHelpers.method(intrinsics, "getOrInsert", 2) { interpreter, thisValue, args ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.getOrInsert")
                val key = args.getOrElse(0) { JSUndefined }
                val existing = map.getEntry(key)
                if (existing != null) {
                    existing.second
                } else {
                    val value = args.getOrElse(1) { JSUndefined }
                    map.setEntry(key, value)
                    value
                }
            },
        )
        mapPrototype.defineDataProperty(
            key = PropertyKey.Str("getOrInsertComputed"),
            value = BuiltinHelpers.method(intrinsics, "getOrInsertComputed", 2) { interpreter, thisValue, args ->
                val map = requireMap(interpreter, thisValue, "Map.prototype.getOrInsertComputed")
                // 规范：先规范化键（-0 → +0），回调接收规范化后的键
                val key = com.muedsa.js.runtime.value.normalizeCollectionKey(
                    args.getOrElse(0) { JSUndefined }
                )
                val callback = args.getOrElse(1) { JSUndefined }
                if (!interpreter.isCallable(callback)) {
                    interpreter.typeError("Map.prototype.getOrInsertComputed requires a callable")
                }
                val existing = map.getEntry(key)
                if (existing != null) {
                    existing.second
                } else {
                    val computed = interpreter.callFunction(callback, JSUndefined, listOf(key))
                    // 回调期间可能插入同键，setEntry 会按规范覆盖该值
                    map.setEntry(key, computed)
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
                // 实时遍历：新增条目追加可被访问，墓碑跳过；重删重加的键作为新记录访问。
                // 遍历期间禁止压缩存储，否则物理下标重排会让游标读到错误的条目。
                map.beginIteration()
                try {
                    var index = map.iterationStartIndex
                    while (index < map.iterationEndIndex) {
                        // 回调可能清空并重新填充集合；跳过已被物理回收的旧逻辑区间。
                        index = maxOf(index, map.iterationStartIndex)
                        val entry = map.entryAtIterationIndex(index)
                        index++
                        if (entry != null) {
                            interpreter.callFunction(
                                callback,
                                thisArg,
                                listOf(entry.second, entry.first, map),
                            )
                        }
                    }
                } finally {
                    map.endIteration()
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
                var index = map.iterationStartIndex
                // 迭代器在首次 next 时才登记，避免"只创建不消费"的迭代器长期阻止压缩；
                // 耗尽时注销并触发一次墓碑回收。
                var started = false
                var finished = false
                fun finish() {
                    if (!finished) {
                        finished = true
                        map.endIteration()
                    }
                }
                IteratorBuiltins.createIteratorWithNext(intrinsics, intrinsics.mapIteratorPrototype) { _, _, _ ->
                    if (!started) {
                        started = true
                        map.beginIteration()
                    }
                    // 实时遍历：跳过墓碑，迭代期间追加的条目会被访问
                    index = maxOf(index, map.iterationStartIndex)
                    var entry: Pair<JSValue, JSValue>? = null
                    while (index < map.iterationEndIndex && entry == null) {
                        entry = map.entryAtIterationIndex(index)
                        index++
                    }
                    if (entry == null) {
                        finish()
                        return@createIteratorWithNext null
                    }
                    val result = JSObject(prototype = intrinsics.objectPrototype)
                    result.defineDataProperty(
                        PropertyKey.Str("value"), valueFor(entry), true, true, true,
                    )
                    result.defineDataProperty(
                        PropertyKey.Str("done"), JSBoolean.FALSE, true, true, true,
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
                    val group = result.getEntry(key)?.second as? JSArray
                    if (group == null) {
                        val created = JSArray(prototype = intrinsics.arrayPrototype)
                        created.setElement(0, value)
                        result.setEntry(key, created)
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
}
