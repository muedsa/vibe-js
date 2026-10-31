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
                IteratorBuiltins.createIteratorWithNext(intrinsics, intrinsics.setIteratorPrototype) { _, _, _ ->
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

        // ---- ES2025 Set 方法集（结果固定为 Set.prototype 的普通 Set） ----
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("union"),
            value = BuiltinHelpers.method(intrinsics, "union", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.union")
                val record = getSetRecord(interpreter, args.getOrElse(0) { JSUndefined })
                val result = createResultSet(interpreter)
                result.values.addAll(set.values)
                iterateSetRecord(interpreter, record) { value ->
                    addValue(interpreter, result, value)
                    true
                }
                result
            },
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("intersection"),
            value = BuiltinHelpers.method(intrinsics, "intersection", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.intersection")
                val record = getSetRecord(interpreter, args.getOrElse(0) { JSUndefined })
                val result = createResultSet(interpreter)
                if (set.values.size <= record.size) {
                    // 接收者更小：按接收者顺序过滤
                    for (value in set.values) {
                        if (hasInSetLike(interpreter, record, value)) {
                            result.values.add(value)
                        }
                    }
                } else {
                    // 接收者更大：按参数 keys 顺序取交集
                    iterateSetRecord(interpreter, record) { value ->
                        if (containsValue(interpreter, set, value)) {
                            addValue(interpreter, result, value)
                        }
                        true
                    }
                }
                result
            },
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("difference"),
            value = BuiltinHelpers.method(intrinsics, "difference", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.difference")
                val record = getSetRecord(interpreter, args.getOrElse(0) { JSUndefined })
                val result = createResultSet(interpreter)
                if (set.values.size <= record.size) {
                    // 接收者更小：按接收者顺序用 has 过滤
                    for (value in set.values) {
                        if (!hasInSetLike(interpreter, record, value)) {
                            result.values.add(value)
                        }
                    }
                } else {
                    // 接收者更大：从副本中删除参数 keys 命中的值
                    result.values.addAll(set.values)
                    iterateSetRecord(interpreter, record) { value ->
                        result.values.removeAll {
                            interpreter.conversions.sameValueZero(it, value)
                        }
                        true
                    }
                }
                result
            },
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("symmetricDifference"),
            value = BuiltinHelpers.method(intrinsics, "symmetricDifference", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.symmetricDifference")
                val record = getSetRecord(interpreter, args.getOrElse(0) { JSUndefined })
                val result = createResultSet(interpreter)
                result.values.addAll(set.values)
                iterateSetRecord(interpreter, record) { value ->
                    if (containsValue(interpreter, set, value)) {
                        // 两边共有：从结果中移除
                        result.values.removeAll {
                            interpreter.conversions.sameValueZero(it, value)
                        }
                    } else {
                        addValue(interpreter, result, value)
                    }
                    true
                }
                result
            },
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("isSubsetOf"),
            value = BuiltinHelpers.method(intrinsics, "isSubsetOf", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.isSubsetOf")
                val record = getSetRecord(interpreter, args.getOrElse(0) { JSUndefined })
                if (set.values.size > record.size) {
                    return@method JSBoolean.FALSE
                }
                for (value in set.values) {
                    if (!hasInSetLike(interpreter, record, value)) {
                        return@method JSBoolean.FALSE
                    }
                }
                JSBoolean.TRUE
            },
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("isSupersetOf"),
            value = BuiltinHelpers.method(intrinsics, "isSupersetOf", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.isSupersetOf")
                val record = getSetRecord(interpreter, args.getOrElse(0) { JSUndefined })
                if (set.values.size < record.size) {
                    return@method JSBoolean.FALSE
                }
                var result = true
                iterateSetRecord(interpreter, record) { value ->
                    if (!containsValue(interpreter, set, value)) {
                        result = false
                        false
                    } else {
                        true
                    }
                }
                JSBoolean.getJsBoolean(result)
            },
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("isDisjointFrom"),
            value = BuiltinHelpers.method(intrinsics, "isDisjointFrom", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.isDisjointFrom")
                val record = getSetRecord(interpreter, args.getOrElse(0) { JSUndefined })
                if (set.values.size <= record.size) {
                    for (value in set.values) {
                        if (hasInSetLike(interpreter, record, value)) {
                            return@method JSBoolean.FALSE
                        }
                    }
                    return@method JSBoolean.TRUE
                }
                var result = true
                iterateSetRecord(interpreter, record) { value ->
                    if (containsValue(interpreter, set, value)) {
                        result = false
                        false
                    } else {
                        true
                    }
                }
                JSBoolean.getJsBoolean(result)
            },
        )

        registry.defineGlobal("Set", setConstructor)
    }

    /** GetSetRecord：校验 set-like 的 size/has/keys。 */
    private class SetRecord(
        val objectValue: JSObject,
        val size: Double,
        val has: JSValue,
        val keys: JSValue,
    )

    private suspend fun getSetRecord(interpreter: Interpreter, value: JSValue): SetRecord {
        if (value !is JSObject) {
            interpreter.typeError("Set-like argument must be an object")
        }
        val rawSize = interpreter.getV(value, PropertyKey.Str("size"))
        val number = interpreter.toNumber(rawSize)
        if (number.isNaN()) {
            interpreter.typeError("Set-like size is NaN")
        }
        if (number < 0.0) {
            interpreter.rangeError("Set-like size must not be negative")
        }
        val has = interpreter.getV(value, PropertyKey.Str("has"))
        if (!interpreter.isCallable(has)) {
            interpreter.typeError("Set-like has is not callable")
        }
        val keys = interpreter.getV(value, PropertyKey.Str("keys"))
        if (!interpreter.isCallable(keys)) {
            interpreter.typeError("Set-like keys is not callable")
        }
        return SetRecord(value, number, has, keys)
    }

    /** 调用 set-like 的 has(value)。 */
    private suspend fun hasInSetLike(
        interpreter: Interpreter,
        record: SetRecord,
        value: JSValue,
    ): Boolean = interpreter.toBoolean(
        interpreter.callFunction(record.has, record.objectValue, listOf(value))
    )

    /** 遍历 set-like 的 keys() 迭代器；回调返回 false 时提前结束。 */
    private suspend fun iterateSetRecord(
        interpreter: Interpreter,
        record: SetRecord,
        action: suspend (JSValue) -> Boolean,
    ) {
        val iterator = interpreter.callFunction(record.keys, record.objectValue, emptyList())
        if (iterator !is JSObject) {
            interpreter.typeError("Set-like keys did not return an object")
        }
        val next = interpreter.getV(iterator, PropertyKey.Str("next"))
        if (!interpreter.isCallable(next)) {
            interpreter.typeError("Set-like iterator has no next method")
        }
        var guard = 0
        while (guard++ < 1_000_000) {
            val step = interpreter.callFunction(next, iterator, emptyList())
            if (step !is JSObject) {
                interpreter.typeError("Iterator result is not an object")
            }
            if (interpreter.toBoolean(interpreter.getV(step, PropertyKey.Str("done")))) return
            val value = interpreter.getV(step, PropertyKey.Str("value"))
            if (!action(value)) {
                // 正常完成下的 IteratorClose：提前结束时调用 return()
                val returnMethod = interpreter.getV(iterator, PropertyKey.Str("return"))
                if (returnMethod !is JSUndefined && returnMethod !is JSNull) {
                    if (!interpreter.isCallable(returnMethod)) {
                        interpreter.typeError("Set-like iterator return is not callable")
                    }
                    val closeResult = interpreter.callFunction(returnMethod, iterator, emptyList())
                    if (closeResult !is JSObject) {
                        interpreter.typeError("Iterator return result is not an object")
                    }
                }
                return
            }
        }
        interpreter.typeError("Set-like iteration did not terminate")
    }

    private fun containsValue(interpreter: Interpreter, set: JSSet, value: JSValue): Boolean =
        set.values.any { interpreter.conversions.sameValueZero(it, value) }

    private fun createResultSet(interpreter: Interpreter): JSSet =
        JSSet(prototype = interpreter.intrinsics.setPrototype)

    private fun requireSet(interpreter: Interpreter, thisValue: JSValue, name: String): JSSet {
        if (thisValue is com.muedsa.js.runtime.value.JSWeakSet) {
            interpreter.typeError("$name requires that 'this' be a Set")
        }
        return thisValue as? JSSet ?: interpreter.typeError("$name requires that 'this' be a Set")
    }

    private fun addValue(interpreter: Interpreter, set: JSSet, value: JSValue) {
        // 规范：Set 中的 -0 归一化为 +0
        val normalized = if (value is JSNumber && value.value == 0.0) JSNumber.ZERO else value
        if (set.values.none { interpreter.conversions.sameValueZero(it, normalized) }) {
            set.values.add(normalized)
        }
    }
}
