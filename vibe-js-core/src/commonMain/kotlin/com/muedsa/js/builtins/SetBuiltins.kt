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

        val setConstructor = BuiltinHelpers.constructorWithNewTarget(
            intrinsics = intrinsics,
            name = "Set",
            length = 0,
            prototypeObject = setPrototype,
        ) { interpreter, _, args, newTarget ->
            if (!interpreter.nativeConstructCall) {
                interpreter.typeError("Constructor Set requires 'new'")
            }
            val set = JSSet(prototype = interpreter.prototypeFromNewTarget(newTarget, setPrototype))
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
                JSNumber(requireSet(interpreter, thisValue, "Set.prototype.size").liveSize.toDouble())
            },
            set = null,
            enumerable = false,
            configurable = true,
        )

        setPrototype.defineDataProperty(
            key = PropertyKey.Str("add"),
            value = BuiltinHelpers.method(intrinsics, "add", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.add")
                // -0 归一化为 +0 由 JSSet.addValue 统一处理
                set.addValue(args.getOrElse(0) { JSUndefined })
                set
            },
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("has"),
            value = BuiltinHelpers.method(intrinsics, "has", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.has")
                JSBoolean.getJsBoolean(set.containsValue(args.getOrElse(0) { JSUndefined }))
            },
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("delete"),
            value = BuiltinHelpers.method(intrinsics, "delete", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.delete")
                JSBoolean.getJsBoolean(set.deleteValue(args.getOrElse(0) { JSUndefined }))
            },
        )
        setPrototype.defineDataProperty(
            key = PropertyKey.Str("clear"),
            value = BuiltinHelpers.method(intrinsics, "clear", 0) { interpreter, thisValue, _ ->
                requireSet(interpreter, thisValue, "Set.prototype.clear").clearValues()
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
                // 实时遍历：新增值追加可被访问，墓碑跳过；重删重加的值作为新记录访问。
                // 遍历期间禁止压缩存储，否则物理下标重排会让游标读到错误的值。
                set.beginIteration()
                try {
                    var index = set.iterationStartIndex
                    while (index < set.iterationEndIndex) {
                        // 嵌套回调也可 clear；每次恢复都依据最新逻辑起点跳过旧区间。
                        index = maxOf(index, set.iterationStartIndex)
                        val value = set.valueAtIterationIndex(index)
                        index++
                        if (value != null) {
                            interpreter.callFunction(callback, thisArg, listOf(value, value, set))
                        }
                    }
                } finally {
                    set.endIteration()
                }
                JSUndefined
            },
        )

        fun iteratorMethod(
            name: String,
            valueFor: (JSValue) -> JSValue,
        ): JSValue {
            val method = BuiltinHelpers.method(intrinsics, name, 0) { interpreter, thisValue, _ ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.$name")
                var index = set.iterationStartIndex
                // 迭代器在首次 next 时才登记；耗尽时注销并触发墓碑回收
                var started = false
                var finished = false
                fun finish() {
                    if (!finished) {
                        finished = true
                        set.endIteration()
                    }
                }
                IteratorBuiltins.createIteratorWithNext(intrinsics, intrinsics.setIteratorPrototype) { _, _, _ ->
                    if (!started) {
                        started = true
                        set.beginIteration()
                    }
                    // 实时遍历：跳过墓碑，迭代期间追加的值会被访问
                    index = maxOf(index, set.iterationStartIndex)
                    var element: JSValue? = null
                    while (index < set.iterationEndIndex && element == null) {
                        element = set.valueAtIterationIndex(index)
                        index++
                    }
                    if (element == null) {
                        finish()
                        return@createIteratorWithNext null
                    }
                    val result = JSObject(prototype = intrinsics.objectPrototype)
                    result.defineDataProperty(
                        PropertyKey.Str("value"), valueFor(element), true, true, true,
                    )
                    result.defineDataProperty(
                        PropertyKey.Str("done"), JSBoolean.FALSE, true, true, true,
                    )
                    result
                }
            }
            setPrototype.defineDataProperty(PropertyKey.Str(name), method)
            return method
        }

        val valuesMethod = iteratorMethod("values") { it }
        // 规范：Set.prototype.keys 与 values 是同一函数对象
        setPrototype.defineDataProperty(PropertyKey.Str("keys"), valuesMethod)
        setPrototype.defineDataProperty(PropertyKey.Sym(intrinsics.symbolIterator), valuesMethod)
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
                for (value in set.liveValues()) result.addValue(value)
                iterateSetRecord(interpreter, record) { value ->
                    result.addValue(value)
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
                if (set.liveSize <= record.size) {
                    // 接收者更小：按接收者顺序过滤
                    for (value in set.liveValues()) {
                        if (hasInSetLike(interpreter, record, value)) {
                            result.addValue(value)
                        }
                    }
                } else {
                    // 接收者更大：按参数 keys 顺序取交集
                    iterateSetRecord(interpreter, record) { value ->
                        if (set.containsValue(value)) {
                            result.addValue(value)
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
                if (set.liveSize <= record.size) {
                    // 接收者更小：按接收者顺序用 has 过滤
                    for (value in set.liveValues()) {
                        if (!hasInSetLike(interpreter, record, value)) {
                            result.addValue(value)
                        }
                    }
                } else {
                    // 接收者更大：从副本中删除参数 keys 命中的值
                    for (value in set.liveValues()) result.addValue(value)
                    iterateSetRecord(interpreter, record) { value ->
                        result.removeValuesMatching {
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
                for (value in set.liveValues()) result.addValue(value)
                iterateSetRecord(interpreter, record) { value ->
                    if (set.containsValue(value)) {
                        // 两边共有：从结果中移除
                        result.removeValuesMatching {
                            interpreter.conversions.sameValueZero(it, value)
                        }
                    } else {
                        result.addValue(value)
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
                if (set.liveSize > record.size) {
                    return@method JSBoolean.FALSE
                }
                var result = true
                // 规范使用接收者的 SetIterator：has 回调中删除/新增的值按实时迭代语义处理
                iterateSetValuesLive(set) { value ->
                    if (!hasInSetLike(interpreter, record, value)) {
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
            key = PropertyKey.Str("isSupersetOf"),
            value = BuiltinHelpers.method(intrinsics, "isSupersetOf", 1) { interpreter, thisValue, args ->
                val set = requireSet(interpreter, thisValue, "Set.prototype.isSupersetOf")
                val record = getSetRecord(interpreter, args.getOrElse(0) { JSUndefined })
                if (set.liveSize < record.size) {
                    return@method JSBoolean.FALSE
                }
                var result = true
                iterateSetRecord(interpreter, record) { value ->
                    if (!set.containsValue(value)) {
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
                if (set.liveSize <= record.size) {
                    // 接收者更小：按接收者的实时迭代顺序用 has 过滤；
                    // has 回调中的删除必须立即生效，不能遍历快照
                    var disjoint = true
                    iterateSetValuesLive(set) { value ->
                        if (hasInSetLike(interpreter, record, value)) {
                            disjoint = false
                            false
                        } else {
                            true
                        }
                    }
                    return@method JSBoolean.getJsBoolean(disjoint)
                }
                var result = true
                iterateSetRecord(interpreter, record) { value ->
                    if (set.containsValue(value)) {
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

    /**
     * 按 Set 的实时迭代顺序访问接收者（对应规范的 SetIterator）。
     *
     * 与 [JSSet.liveValues] 快照不同：回调执行期间的 delete/clear 会让后续
     * 访问立即跳过对应值，add 的值按插入顺序追加访问；这保证
     * `isSubsetOf`/`isDisjointFrom` 的 has 回调修改接收者时行为符合规范。
     *
     * @param action 返回 false 时提前结束迭代
     */
    private suspend fun iterateSetValuesLive(set: JSSet, action: suspend (JSValue) -> Boolean) {
        set.beginIteration()
        try {
            var index = set.iterationStartIndex
            while (index < set.iterationEndIndex) {
                // 嵌套 clear 会推进逻辑起点，游标需要跳到新起点
                index = maxOf(index, set.iterationStartIndex)
                val value = set.valueAtIterationIndex(index)
                index++
                if (value != null && !action(value)) return
            }
        } finally {
            set.endIteration()
        }
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
        // GetSetRecord 在 NaN 校验后先向零取整，再判断负数；-0.5 应作为零接受。
        // 复用已经转换的数值，避免再次触发 valueOf，且后续大小分支必须使用整数结果。
        val size = interpreter.conversions.toIntegerOrInfinity(number)
        if (size < 0.0) {
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
        return SetRecord(value, size, has, keys)
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
        while (true) {
            // 有限大输入不得被误判为未终止；无限迭代由统一配额与取消负责中止。
            interpreter.checkExecution()
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
    }

    private fun createResultSet(interpreter: Interpreter): JSSet =
        JSSet(prototype = interpreter.intrinsics.setPrototype)

    private fun requireSet(interpreter: Interpreter, thisValue: JSValue, name: String): JSSet {
        if (thisValue is com.muedsa.js.runtime.value.JSWeakSet) {
            interpreter.typeError("$name requires that 'this' be a Set")
        }
        return thisValue as? JSSet ?: interpreter.typeError("$name requires that 'this' be a Set")
    }
}
