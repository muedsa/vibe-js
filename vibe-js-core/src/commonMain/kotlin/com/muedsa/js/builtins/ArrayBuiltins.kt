package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyDescriptor
import com.muedsa.js.runtime.value.PropertyKey
import kotlin.math.floor

/**
 * Array 构造器与 Array.prototype。
 *
 * 阶段 A 覆盖常用的构造、迭代、查找、增删与排序方法；
 * 迭代方法遵循"跳过空洞"的规范行为。
 */
object ArrayBuiltins {

    /** array-like 快照上限，避免 length=Infinity/超大对象造成无界分配。 */
    private const val MAX_ARRAY_LIKE_LENGTH = 100_000

    /** Number.MAX_SAFE_INTEGER：concat 结果长度上限。 */
    private const val MAX_SAFE_INTEGER = 9007199254740991L

    /** array-like 快照；保留原对象用于回调 third argument 与写回。 */
    private class ArrayLikeSnapshot(
        elements: MutableList<JSValue?>,
        prototype: JSObject,
        val original: JSObject,
        val originalLength: Int,
    ) : JSArray(elements, prototype)

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val arrayPrototype = intrinsics.arrayPrototype

        val arrayConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "Array",
            length = 1,
            prototypeObject = arrayPrototype,
        ) { interpreter, _, args ->
            createArray(interpreter, arrayPrototype, args)
        }

        arrayPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = arrayConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        // Array.prototype[@@unscopables]：with 环境中屏蔽这些方法名
        val unscopableList = JSObject(prototype = null)
        val unscopableMethods = listOf(
            "copyWithin", "entries", "fill", "find", "findIndex",
            "flat", "flatMap", "includes", "keys", "values",
            "at", "findLast", "findLastIndex",
            "toReversed", "toSorted", "toSpliced",
        )
        for (name in unscopableMethods) {
            unscopableList.defineDataProperty(
                key = PropertyKey.Str(name),
                value = JSBoolean.TRUE,
                writable = true,
                enumerable = true,
                configurable = true,
            )
        }
        arrayPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolUnscopables),
            value = unscopableList,
            writable = false,
            enumerable = false,
            configurable = true,
        )

        // Array[@@species]：返回 this，子类方法默认沿用自身作为结果构造器
        arrayConstructor.defineAccessorProperty(
            key = PropertyKey.Sym(intrinsics.symbolSpecies),
            get = BuiltinHelpers.method(intrinsics, "get [Symbol.species]", 0) { _, thisValue, _ ->
                thisValue
            },
            set = null,
            enumerable = false,
            configurable = true,
        )

        installPrototypeMethods(registry)
        installStaticMethods(registry, arrayConstructor)

        registry.defineGlobal("Array", arrayConstructor)
    }

    /**
     * Array(...) 的语义：
     * - 无参数：空数组
     * - 单个数字参数：指定长度的空数组
     * - 其他：以参数为元素
     */
    private fun createArray(
        interpreter: Interpreter,
        arrayPrototype: JSObject,
        args: List<JSValue>,
    ): JSArray {
        if (args.isEmpty()) return JSArray(prototype = arrayPrototype)

        if (args.size == 1 && args[0] is JSNumber) {
            val lengthValue = (args[0] as JSNumber).value
            if (lengthValue.isNaN() || lengthValue < 0.0 ||
                lengthValue != floor(lengthValue) || lengthValue > JSArray.MAX_LENGTH.toDouble()
            ) {
                interpreter.rangeError("Invalid array length")
            }
            val array = JSArray(prototype = arrayPrototype)
            array.setLength(lengthValue.toLong())
            return array
        }

        val elements = ArrayList<JSValue?>(args.size)
        elements.addAll(args)
        return JSArray(elements, prototype = arrayPrototype)
    }

    private fun installPrototypeMethods(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val arrayPrototype = intrinsics.arrayPrototype

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("push"),
            value = BuiltinHelpers.method(intrinsics, "push", 1) { interpreter, thisValue, args ->
                // 真实数组按规范直接对原对象 Put，length 增长交给数组异质逻辑，
                // 这样长度到达 2^32-1 后 push 能正确抛 RangeError
                if (thisValue is JSArray) {
                    var index = thisValue.lengthLong
                    for (argument in args) {
                        interpreter.putV(
                            thisValue,
                            PropertyKey.Str(index.toString()),
                            argument,
                            strict = true,
                        )
                        index++
                    }
                    interpreter.putV(
                        thisValue,
                        PropertyKey.LENGTH,
                        JSNumber(index.toDouble()),
                        strict = true,
                    )
                    return@method JSNumber(index.toDouble())
                }
                val array = requireArray(interpreter, thisValue, "Array.prototype.push")
                for (argument in args) {
                    array.setElement(array.length, argument)
                }
                syncArrayLike(interpreter, array)
                JSNumber(array.length.toDouble())
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("pop"),
            value = BuiltinHelpers.method(intrinsics, "pop", 0) { interpreter, thisValue, _ ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.pop")
                if (array.length == 0) {
                    JSUndefined
                } else {
                    val index = array.length - 1
                    val result = array.getElement(index) ?: JSUndefined
                    // 规范：Delete 后 Set length；不可配置元素会阻止删除并抛 TypeError
                    if (!array.deleteOwnProperty(PropertyKey.Str(index.toString()))) {
                        interpreter.typeError("Cannot delete property '${index}' of array")
                    }
                    array.setLength(index.toLong())
                    syncArrayLike(interpreter, array)
                    result
                }
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("shift"),
            value = BuiltinHelpers.method(intrinsics, "shift", 0) { interpreter, thisValue, _ ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.shift")
                val length = array.length
                if (length == 0) {
                    JSUndefined
                } else {
                    val result = array.getElement(0) ?: JSUndefined
                    for (index in 1 until length) {
                        val value = array.getElement(index)
                        if (value == null) {
                            array.removeElement(index - 1)
                        } else {
                            array.setElement(index - 1, value)
                        }
                    }
                    array.removeElement((length - 1).toLong())
                    array.setLength((length - 1).toLong())
                    syncArrayLike(interpreter, array)
                    result
                }
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("unshift"),
            value = BuiltinHelpers.method(intrinsics, "unshift", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.unshift")
                val length = array.length
                val count = args.size
                for (index in length - 1 downTo 0) {
                    val value = array.getElement(index)
                    if (value == null) {
                        array.removeElement(index + count)
                    } else {
                        array.setElement(index + count, value)
                    }
                }
                args.forEachIndexed { index, value -> array.setElement(index, value) }
                syncArrayLike(interpreter, array)
                JSNumber(array.length.toDouble())
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("join"),
            value = BuiltinHelpers.method(intrinsics, "join", 1) { interpreter, thisValue, args ->
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.join")
                val separator = when (val value = args.getOrNull(0)) {
                    null, is JSUndefined -> ","
                    else -> interpreter.toStringValue(value)
                }
                JSString(joinElements(interpreter, objectValue, lengthOf(interpreter, objectValue), separator))
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            value = BuiltinHelpers.method(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.toString")
                JSString(joinElements(interpreter, objectValue, lengthOf(interpreter, objectValue), ","))
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("toLocaleString"),
            value = BuiltinHelpers.method(intrinsics, "toLocaleString", 0) { interpreter, thisValue, _ ->
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.toLocaleString")
                val length = lengthOf(interpreter, objectValue)
                val parts = ArrayList<String>()
                var index = 0L
                while (index < length) {
                    val element = elementAt(interpreter, objectValue, index)
                    parts.add(
                        if (element == null || element is JSNull || element is JSUndefined) {
                            ""
                        } else {
                            val method = interpreter.getV(element, PropertyKey.Str("toLocaleString"))
                            interpreter.toStringValue(
                                interpreter.callFunction(method, element, emptyList())
                            )
                        }
                    )
                    index++
                }
                JSString(parts.joinToString(","))
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("slice"),
            value = BuiltinHelpers.method(intrinsics, "slice", 2) { interpreter, thisValue, args ->
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.slice")
                val length = lengthOf(interpreter, objectValue)
                var start = args.getOrNull(0)?.let { interpreter.toNumber(it).toLong() } ?: 0L
                var end = args.getOrNull(1)?.let { interpreter.toNumber(it).toLong() } ?: length
                if (start < 0) start += length
                if (start < 0) start = 0
                if (start > length) start = length
                if (end < 0) end += length
                if (end < 0) end = 0
                if (end > length) end = length
                if (end < start) end = start

                val target = arraySpeciesCreate(interpreter, objectValue, end - start)
                var index = start
                var to = 0L
                while (index < end) {
                    val element = elementAt(interpreter, objectValue, index)
                    if (element != null) {
                        createDataPropertyOrThrow(interpreter, target, to, element)
                    }
                    index++
                    to++
                }
                target
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("indexOf"),
            value = BuiltinHelpers.method(intrinsics, "indexOf", 1) { interpreter, thisValue, args ->
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.indexOf")
                val searchElement = args.getOrNull(0) ?: JSUndefined
                val length = lengthOf(interpreter, objectValue)
                var fromIndex = args.getOrNull(1)?.let { interpreter.toNumber(it).toLong() } ?: 0L
                if (fromIndex < 0) fromIndex += length
                if (fromIndex < 0) fromIndex = 0

                var result = -1L
                var index = fromIndex
                while (index < length) {
                    val element = elementAt(interpreter, objectValue, index)
                    if (element != null && interpreter.strictEquals(element, searchElement)) {
                        result = index
                        break
                    }
                    index++
                }
                JSNumber(result.toDouble())
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("lastIndexOf"),
            value = BuiltinHelpers.method(intrinsics, "lastIndexOf", 1) { interpreter, thisValue, args ->
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.lastIndexOf")
                val searchElement = args.getOrNull(0) ?: JSUndefined
                val length = lengthOf(interpreter, objectValue)
                var fromIndex = args.getOrNull(1)?.let { interpreter.toNumber(it).toLong() }
                    ?: (length - 1)
                if (fromIndex < 0) fromIndex += length
                if (fromIndex >= length) fromIndex = length - 1

                var result = -1L
                var index = fromIndex
                while (index >= 0) {
                    val element = elementAt(interpreter, objectValue, index)
                    if (element != null && interpreter.strictEquals(element, searchElement)) {
                        result = index
                        break
                    }
                    index--
                }
                JSNumber(result.toDouble())
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("includes"),
            value = BuiltinHelpers.method(intrinsics, "includes", 1) { interpreter, thisValue, args ->
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.includes")
                val searchElement = args.getOrNull(0) ?: JSUndefined
                val length = lengthOf(interpreter, objectValue)
                var fromIndex = args.getOrNull(1)?.let { interpreter.toNumber(it).toLong() } ?: 0L
                if (fromIndex < 0) fromIndex += length
                if (fromIndex < 0) fromIndex = 0

                var result = false
                var index = fromIndex
                while (index < length) {
                    // 规范要求 includes 将数组空洞视为 undefined
                    val element = elementAt(interpreter, objectValue, index) ?: JSUndefined
                    if (interpreter.conversions.sameValueZero(element, searchElement)) {
                        result = true
                        break
                    }
                    index++
                }
                JSBoolean.getJsBoolean(result)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("forEach"),
            value = BuiltinHelpers.method(intrinsics, "forEach", 1) { interpreter, thisValue, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.forEach")
                val length = lengthOf(interpreter, objectValue)
                // 规范：先 ToObject 并读取 length，再校验 callback
                requireCallback(interpreter, callback, "Array.prototype.forEach")
                var index = 0L
                while (index < length) {
                    val element = elementAt(interpreter, objectValue, index)
                    if (element != null) {
                        callCallback(interpreter, callback, thisArg, element, index, objectValue)
                    }
                    index++
                }
                JSUndefined
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("map"),
            value = BuiltinHelpers.method(intrinsics, "map", 1) { interpreter, thisValue, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.map")
                val length = lengthOf(interpreter, objectValue)
                requireCallback(interpreter, callback, "Array.prototype.map")
                // ArrayCreate：长度超过 2^32-1 抛 RangeError
                if (length > JSArray.MAX_LENGTH) interpreter.rangeError("Invalid array length")
                // ArraySpeciesCreate(O, len)：结果容器由 species 决定
                val target = arraySpeciesCreate(interpreter, objectValue, length)
                var index = 0L
                while (index < length) {
                    val element = elementAt(interpreter, objectValue, index)
                    if (element != null) {
                        val mapped = callCallback(
                            interpreter, callback, thisArg, element, index, objectValue
                        )
                        createDataPropertyOrThrow(interpreter, target, index, mapped)
                    }
                    index++
                }
                target
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("filter"),
            value = BuiltinHelpers.method(intrinsics, "filter", 1) { interpreter, thisValue, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.filter")
                val length = lengthOf(interpreter, objectValue)
                requireCallback(interpreter, callback, "Array.prototype.filter")
                val target = arraySpeciesCreate(interpreter, objectValue, 0)
                var index = 0L
                var to = 0L
                while (index < length) {
                    val element = elementAt(interpreter, objectValue, index)
                    if (element != null) {
                        val keep = callCallback(
                            interpreter, callback, thisArg, element, index, objectValue
                        )
                        if (interpreter.toBoolean(keep)) {
                            createDataPropertyOrThrow(interpreter, target, to, element)
                            to++
                        }
                    }
                    index++
                }
                target
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("every"),
            value = BuiltinHelpers.method(intrinsics, "every", 1) { interpreter, thisValue, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.every")
                val length = lengthOf(interpreter, objectValue)
                requireCallback(interpreter, callback, "Array.prototype.every")
                var result = true
                var index = 0L
                while (index < length) {
                    val element = elementAt(interpreter, objectValue, index)
                    if (element != null) {
                        if (!interpreter.toBoolean(
                                callCallback(interpreter, callback, thisArg, element, index, objectValue)
                            )
                        ) {
                            result = false
                            break
                        }
                    }
                    index++
                }
                JSBoolean.getJsBoolean(result)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("some"),
            value = BuiltinHelpers.method(intrinsics, "some", 1) { interpreter, thisValue, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.some")
                val length = lengthOf(interpreter, objectValue)
                requireCallback(interpreter, callback, "Array.prototype.some")
                var result = false
                var index = 0L
                while (index < length) {
                    val element = elementAt(interpreter, objectValue, index)
                    if (element != null && interpreter.toBoolean(
                            callCallback(interpreter, callback, thisArg, element, index, objectValue)
                        )
                    ) {
                        result = true
                        break
                    }
                    index++
                }
                JSBoolean.getJsBoolean(result)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("find"),
            value = BuiltinHelpers.method(intrinsics, "find", 1) { interpreter, thisValue, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.find")
                val length = lengthOf(interpreter, objectValue)
                requireCallback(interpreter, callback, "Array.prototype.find")
                var index = 0L
                while (index < length) {
                    val element = elementAt(interpreter, objectValue, index)
                    if (element != null && interpreter.toBoolean(
                            callCallback(interpreter, callback, thisArg, element, index, objectValue)
                        )
                    ) {
                        return@method element
                    }
                    index++
                }
                JSUndefined
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("findIndex"),
            value = BuiltinHelpers.method(intrinsics, "findIndex", 1) { interpreter, thisValue, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.findIndex")
                val length = lengthOf(interpreter, objectValue)
                requireCallback(interpreter, callback, "Array.prototype.findIndex")
                var index = 0L
                while (index < length) {
                    val element = elementAt(interpreter, objectValue, index)
                    if (element != null && interpreter.toBoolean(
                            callCallback(interpreter, callback, thisArg, element, index, objectValue)
                        )
                    ) {
                        return@method JSNumber(index.toDouble())
                    }
                    index++
                }
                JSNumber(-1.0)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("findLast"),
            value = BuiltinHelpers.method(intrinsics, "findLast", 1) { interpreter, thisValue, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.findLast")
                val length = lengthOf(interpreter, objectValue)
                requireCallback(interpreter, callback, "Array.prototype.findLast")
                var index = length - 1
                while (index >= 0) {
                    // 规范使用 Get（空洞视为 undefined），不再 HasProperty
                    val element = interpreter.getV(objectValue, PropertyKey.Str(index.toString()))
                    if (interpreter.toBoolean(
                            callCallback(interpreter, callback, thisArg, element, index, objectValue)
                        )
                    ) {
                        return@method element
                    }
                    index--
                }
                JSUndefined
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("findLastIndex"),
            value = BuiltinHelpers.method(intrinsics, "findLastIndex", 1) { interpreter, thisValue, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.findLastIndex")
                val length = lengthOf(interpreter, objectValue)
                requireCallback(interpreter, callback, "Array.prototype.findLastIndex")
                var index = length - 1
                while (index >= 0) {
                    val element = interpreter.getV(objectValue, PropertyKey.Str(index.toString()))
                    if (interpreter.toBoolean(
                            callCallback(interpreter, callback, thisArg, element, index, objectValue)
                        )
                    ) {
                        return@method JSNumber(index.toDouble())
                    }
                    index--
                }
                JSNumber(-1.0)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("reduce"),
            value = BuiltinHelpers.method(intrinsics, "reduce", 1) { interpreter, thisValue, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.reduce")
                val length = lengthOf(interpreter, objectValue)
                requireCallback(interpreter, callback, "Array.prototype.reduce")

                var accumulator: JSValue
                var startIndex: Long
                if (args.size >= 2) {
                    accumulator = args[1]
                    startIndex = 0
                } else {
                    var initialIndex = -1L
                    var index = 0L
                    while (index < length) {
                        if (elementAt(interpreter, objectValue, index) != null) {
                            initialIndex = index
                            break
                        }
                        index++
                    }
                    if (initialIndex < 0) {
                        interpreter.typeError("Reduce of empty array with no initial value")
                    }
                    accumulator = elementAt(interpreter, objectValue, initialIndex) ?: JSUndefined
                    startIndex = initialIndex + 1
                }

                var index = startIndex
                while (index < length) {
                    val element = elementAt(interpreter, objectValue, index)
                    if (element != null) {
                        accumulator = interpreter.callFunction(
                            callback,
                            JSUndefined,
                            listOf(accumulator, element, JSNumber(index.toDouble()), objectValue),
                        )
                    }
                    index++
                }
                accumulator
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("reduceRight"),
            value = BuiltinHelpers.method(intrinsics, "reduceRight", 1) { interpreter, thisValue, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.reduceRight")
                val length = lengthOf(interpreter, objectValue)
                requireCallback(interpreter, callback, "Array.prototype.reduceRight")

                var accumulator: JSValue
                var startIndex: Long
                if (args.size >= 2) {
                    accumulator = args[1]
                    startIndex = length - 1
                } else {
                    var initialIndex = -1L
                    var index = length - 1
                    while (index >= 0) {
                        if (elementAt(interpreter, objectValue, index) != null) {
                            initialIndex = index
                            break
                        }
                        index--
                    }
                    if (initialIndex < 0) {
                        interpreter.typeError("Reduce of empty array with no initial value")
                    }
                    accumulator = elementAt(interpreter, objectValue, initialIndex) ?: JSUndefined
                    startIndex = initialIndex - 1
                }

                var index = startIndex
                while (index >= 0) {
                    val element = elementAt(interpreter, objectValue, index)
                    if (element != null) {
                        accumulator = interpreter.callFunction(
                            callback,
                            JSUndefined,
                            listOf(accumulator, element, JSNumber(index.toDouble()), objectValue),
                        )
                    }
                    index--
                }
                accumulator
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("concat"),
            value = BuiltinHelpers.method(intrinsics, "concat", 1) { interpreter, thisValue, args ->
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.concat")
                val target = arraySpeciesCreate(interpreter, objectValue, 0)
                var n = 0L
                suspend fun appendSource(source: JSValue) {
                    if (isConcatSpreadable(interpreter, source)) {
                        val sourceObject = source as JSObject
                        val length = lengthOf(interpreter, sourceObject)
                        // 结果长度超过 2^53-1 抛 TypeError（在遍历前检查）
                        if (length > MAX_SAFE_INTEGER - n) {
                            interpreter.typeError("Invalid array length")
                        }
                        var index = 0L
                        while (index < length) {
                            val key = PropertyKey.Str(index.toString())
                            if (sourceObject.hasProperty(key)) {
                                val element = interpreter.getV(sourceObject, key)
                                createDataPropertyOrThrow(interpreter, target, n, element)
                            }
                            n++
                            index++
                        }
                    } else {
                        createDataPropertyOrThrow(interpreter, target, n, source)
                        n++
                    }
                }
                appendSource(objectValue)
                for (argument in args) appendSource(argument)
                // 规范：Set(A, "length", n, true)，保证空洞也被计入长度
                interpreter.putV(target, PropertyKey.LENGTH, JSNumber(n.toDouble()), strict = true)
                target
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("reverse"),
            value = BuiltinHelpers.method(intrinsics, "reverse", 0) { interpreter, thisValue, _ ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.reverse")
                val length = array.length
                for (index in 0 until length / 2) {
                    val opposite = length - 1 - index
                    val lower = array.getElement(index)
                    val upper = array.getElement(opposite)
                    if (upper == null) array.removeElement(index.toLong()) else array.setElement(index, upper)
                    if (lower == null) array.removeElement(opposite.toLong()) else array.setElement(opposite, lower)
                }
                syncArrayLike(interpreter, array)
                array
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("sort"),
            value = BuiltinHelpers.method(intrinsics, "sort", 1) { interpreter, thisValue, args ->
                val comparator = args.getOrNull(0)
                if (comparator != null && comparator !is JSUndefined && !interpreter.isCallable(comparator)) {
                    interpreter.typeError("The comparison function must be either a function or undefined")
                }
                val array = requireArray(interpreter, thisValue, "Array.prototype.sort")
                sortElements(interpreter, array, comparator)
                syncArrayLike(interpreter, array)
                array
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("splice"),
            value = BuiltinHelpers.method(intrinsics, "splice", 2) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.splice")
                val length = array.length
                var start = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                if (start < 0) start += length
                start = start.coerceIn(0, length)

                val deleteCount = when {
                    args.isEmpty() -> 0
                    args.size == 1 -> length - start
                    else -> interpreter.toNumber(args[1]).toInt().coerceIn(0, length - start)
                }

                val items = if (args.size > 2) args.subList(2, args.size) else emptyList()
                // ArraySpeciesCreate(O, actualDeleteCount)，拷贝通过 CreateDataProperty
                val removed = arraySpeciesCreate(interpreter, array, deleteCount.toLong())
                for (offset in 0 until deleteCount) {
                    if (array.hasElement(start + offset)) {
                        val value = array.getElement(start + offset) ?: continue
                        createDataPropertyOrThrow(interpreter, removed, offset.toLong(), value)
                    }
                }
                for (offset in 0 until deleteCount) {
                    array.removeElement(start + offset)
                }
                // 先整体右移/左移尾部元素，再写入新增元素
                val tail = length - start - deleteCount
                if (items.size > deleteCount) {
                    val shift = items.size - deleteCount
                    for (index in tail - 1 downTo 0) {
                        val value = array.getElement(start + deleteCount + index)
                        if (value == null) {
                            array.removeElement((start + items.size + index).toLong())
                        } else {
                            array.setElement(start + items.size + index, value)
                        }
                    }
                } else if (items.size < deleteCount) {
                    val shift = deleteCount - items.size
                    for (index in 0 until tail) {
                        val from = start + deleteCount + index
                        val value = array.getElement(from)
                        if (value == null) {
                            array.removeElement((from - shift).toLong())
                        } else {
                            array.setElement(from - shift, value)
                        }
                    }
                    for (index in length - 1 downTo length - shift) {
                        array.removeElement(index.toLong())
                    }
                }
                items.forEachIndexed { index, value -> array.setElement(start + index, value) }
                array.setLength((length - deleteCount + items.size).toLong())
                syncArrayLike(interpreter, array)
                // 规范：Set(A, "length", actualDeleteCount, true)
                interpreter.putV(
                    removed,
                    PropertyKey.LENGTH,
                    JSNumber(deleteCount.toDouble()),
                    strict = true,
                )

                removed
            },
        )

        // ---- change-array-by-copy：均通过 ArrayCreate 返回新数组（忽略 species） ----
        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("toReversed"),
            value = BuiltinHelpers.method(intrinsics, "toReversed", 0) { interpreter, thisValue, _ ->
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.toReversed")
                val length = lengthOf(interpreter, objectValue)
                val target = createArrayWithLength(interpreter, length)
                var index = 0L
                while (index < length) {
                    // 读取每一个索引（含空洞，原型值也算），结果不留空洞
                    val value = interpreter.getV(
                        objectValue,
                        PropertyKey.Str((length - index - 1).toString()),
                    )
                    createDataPropertyOrThrow(interpreter, target, index, value)
                    index++
                }
                target
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("toSorted"),
            value = BuiltinHelpers.method(intrinsics, "toSorted", 1) { interpreter, thisValue, args ->
                val comparator = args.getOrNull(0)
                if (comparator != null && comparator !is JSUndefined && !interpreter.isCallable(comparator)) {
                    interpreter.typeError("The comparison function must be either a function or undefined")
                }
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.toSorted")
                val length = lengthOf(interpreter, objectValue)
                val target = createArrayWithLength(interpreter, length)
                var index = 0L
                while (index < length) {
                    val value = interpreter.getV(objectValue, PropertyKey.Str(index.toString()))
                    createDataPropertyOrThrow(interpreter, target, index, value)
                    index++
                }
                // 复制完成后再排序（比较器在全部 Get 之后调用）
                sortElements(interpreter, target, comparator)
                target
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("toSpliced"),
            value = BuiltinHelpers.method(intrinsics, "toSpliced", 2) { interpreter, thisValue, args ->
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.toSpliced")
                val length = lengthOf(interpreter, objectValue)
                val relativeStart = toIntegerOrInfinity(
                    interpreter,
                    args.getOrElse(0) { JSUndefined },
                )
                val actualStart = if (relativeStart < 0) {
                    (length + relativeStart).coerceAtLeast(0)
                } else {
                    relativeStart.coerceAtMost(length)
                }
                val actualDeleteCount = when {
                    args.isEmpty() -> 0L
                    args.size == 1 -> length - actualStart
                    else -> toIntegerOrInfinity(interpreter, args[1])
                        .coerceIn(0, length - actualStart)
                }
                val insertCount = (args.size - 2).coerceAtLeast(0).toLong()
                val newLength = length - actualDeleteCount + insertCount
                // 规范：先检查 2^53-1（TypeError），再由 ArrayCreate 检查 2^32-1（RangeError）
                if (newLength > MAX_SAFE_INTEGER) {
                    interpreter.typeError("Invalid array length")
                }
                val target = createArrayWithLength(interpreter, newLength)

                var index = 0L
                while (index < actualStart) {
                    val value = interpreter.getV(objectValue, PropertyKey.Str(index.toString()))
                    createDataPropertyOrThrow(interpreter, target, index, value)
                    index++
                }
                for (itemIndex in 0 until insertCount) {
                    createDataPropertyOrThrow(
                        interpreter,
                        target,
                        actualStart + itemIndex,
                        args[(2 + itemIndex).toInt()],
                    )
                }
                index = actualStart + insertCount
                while (index < newLength) {
                    val from = index + actualDeleteCount - insertCount
                    val value = interpreter.getV(objectValue, PropertyKey.Str(from.toString()))
                    createDataPropertyOrThrow(interpreter, target, index, value)
                    index++
                }
                target
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("with"),
            value = BuiltinHelpers.method(intrinsics, "with", 2) { interpreter, thisValue, args ->
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.with")
                val length = lengthOf(interpreter, objectValue)
                val relativeIndex = toIntegerOrInfinity(
                    interpreter,
                    args.getOrElse(0) { JSUndefined },
                )
                val actualIndex = if (relativeIndex < 0) length + relativeIndex else relativeIndex
                if (actualIndex < 0 || actualIndex >= length) {
                    interpreter.rangeError("Invalid index")
                }
                val replacement = args.getOrElse(1) { JSUndefined }
                val target = createArrayWithLength(interpreter, length)
                var index = 0L
                while (index < length) {
                    val value = if (index == actualIndex) {
                        replacement
                    } else {
                        interpreter.getV(objectValue, PropertyKey.Str(index.toString()))
                    }
                    createDataPropertyOrThrow(interpreter, target, index, value)
                    index++
                }
                target
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("at"),
            value = BuiltinHelpers.method(intrinsics, "at", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.at")
                var index = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                if (index < 0) index += array.length
                if (index < 0 || index >= array.length) JSUndefined
                else array.getElement(index) ?: JSUndefined
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("flat"),
            value = BuiltinHelpers.method(intrinsics, "flat", 0) { interpreter, thisValue, args ->
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.flat")
                val length = lengthOf(interpreter, objectValue)
                val depthValue = args.getOrNull(0)
                val depth = if (depthValue == null || depthValue is JSUndefined) {
                    1L
                } else {
                    toFlattenDepth(interpreter, depthValue)
                }
                val target = arraySpeciesCreate(interpreter, objectValue, 0)
                flattenIntoArray(interpreter, target, objectValue, length, 0, depth)
                target
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("flatMap"),
            value = BuiltinHelpers.method(intrinsics, "flatMap", 1) { interpreter, thisValue, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.flatMap")
                val length = lengthOf(interpreter, objectValue)
                requireCallback(interpreter, callback, "Array.prototype.flatMap")
                val target = arraySpeciesCreate(interpreter, objectValue, 0)
                flattenIntoArray(
                    interpreter = interpreter,
                    target = target,
                    source = objectValue,
                    sourceLength = length,
                    start = 0,
                    depth = 1,
                    mapper = callback,
                    thisArg = thisArg,
                )
                target
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("fill"),
            value = BuiltinHelpers.method(intrinsics, "fill", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.fill")
                val value = args.getOrElse(0) { JSUndefined }
                val length = array.length
                var start = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: 0
                var end = args.getOrNull(2)?.let { interpreter.toNumber(it).toInt() } ?: length
                if (start < 0) start += length
                if (start < 0) start = 0
                if (start > length) start = length
                if (end < 0) end += length
                if (end < 0) end = 0
                if (end > length) end = length
                for (index in start until end) {
                    array.setElement(index, value)
                }
                syncArrayLike(interpreter, array)
                array
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("copyWithin"),
            value = BuiltinHelpers.method(intrinsics, "copyWithin", 2) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.copyWithin")
                val length = array.length
                var target = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                var start = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: 0
                var end = args.getOrNull(2)?.let { interpreter.toNumber(it).toInt() } ?: length
                if (target < 0) target += length
                if (start < 0) start += length
                if (end < 0) end += length
                target = target.coerceIn(0, length)
                start = start.coerceIn(0, length)
                end = end.coerceIn(0, length)
                val count = minOf(end - start, length - target).coerceAtLeast(0)
                val source = ArrayList<JSValue?>(count)
                for (index in 0 until count) {
                    source.add(array.getElement(start + index))
                }
                for (index in 0 until count) {
                    val value = source[index]
                    if (value == null) {
                        array.removeElement(target + index)
                    } else {
                        array.setElement(target + index, value)
                    }
                }
                syncArrayLike(interpreter, array)
                array
            },
        )


        val valuesIterator = BuiltinHelpers.method(intrinsics, "values", 0) { interpreter, thisValue, _ ->
            val array = requireArray(interpreter, thisValue, "Array.prototype.values")
            // 快照数组元素；不能调用 iterateValues（会通过 @@iterator 递归回到此方法）
            val values = ArrayList<JSValue>(array.length)
            for (index in 0 until array.length) {
                values.add(array.getElement(index) ?: JSUndefined)
            }
            IteratorBuiltins.createIterator(intrinsics, values, "Array Iterator")
        }
        arrayPrototype.defineDataProperty(PropertyKey.Str("values"), valuesIterator)
        arrayPrototype.defineDataProperty(PropertyKey.Sym(intrinsics.symbolIterator), valuesIterator)
        intrinsics.arrayValuesFunction = valuesIterator
        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("keys"),
            value = BuiltinHelpers.method(intrinsics, "keys", 0) { interpreter, thisValue, _ ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.keys")
                val indices = (0 until array.length).map { JSNumber(it.toDouble()) }
                IteratorBuiltins.createIterator(intrinsics, indices, "Array Iterator")
            },
        )
        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("entries"),
            value = BuiltinHelpers.method(intrinsics, "entries", 0) { interpreter, thisValue, _ ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.entries")
                val entries = (0 until array.length).map { index ->
                    JSArray(
                        mutableListOf<JSValue?>(
                            JSNumber(index.toDouble()),
                            array.getElement(index) ?: JSUndefined,
                        ),
                        prototype = intrinsics.arrayPrototype,
                    )
                }
                IteratorBuiltins.createIterator(intrinsics, entries, "Array Iterator")
            },
        )
    }

    private fun installStaticMethods(registry: BuiltinRegistry, arrayConstructor: com.muedsa.js.runtime.value.JSNativeFunction) {
        val intrinsics = registry.intrinsics
        val arrayPrototype = intrinsics.arrayPrototype

        arrayConstructor.defineDataProperty(
            key = PropertyKey.Str("isArray"),
            value = BuiltinHelpers.method(intrinsics, "isArray", 1) { _, _, args ->
                JSBoolean.getJsBoolean(args.getOrNull(0) is JSArray)
            },
        )

        arrayConstructor.defineDataProperty(
            key = PropertyKey.Str("of"),
            value = BuiltinHelpers.method(intrinsics, "of", 0) { _, _, args ->
                val elements = ArrayList<JSValue?>(args.size)
                elements.addAll(args)
                JSArray(elements, prototype = arrayPrototype)
            },
        )

        arrayConstructor.defineDataProperty(
            key = PropertyKey.Str("from"),
            value = BuiltinHelpers.method(intrinsics, "from", 1) { interpreter, thisValue, args ->
                val source = args.getOrNull(0) ?: JSUndefined
                val mapFunction = args.getOrNull(1)
                val thisArg = args.getOrNull(2) ?: JSUndefined
                if (mapFunction != null && mapFunction !is JSUndefined &&
                    !interpreter.isCallable(mapFunction)
                ) {
                    interpreter.typeError("Array.from: mapfn is not a function")
                }
                val mapFn = if (mapFunction != null && interpreter.isCallable(mapFunction)) {
                    mapFunction
                } else {
                    null
                }

                // C = this；可构造时先创建结果对象，构造异常先于迭代器获取
                val array: JSObject = if (interpreter.isConstructor(thisValue)) {
                    interpreter.construct(thisValue, emptyList()) as? JSObject
                        ?: interpreter.typeError("Array.from constructor did not return an object")
                } else {
                    JSArray(prototype = arrayPrototype)
                }

                suspend fun applyMap(item: JSValue, index: Int): JSValue =
                    if (mapFn != null) {
                        interpreter.callFunction(
                            mapFn,
                            thisArg,
                            listOf(item, JSNumber(index.toDouble())),
                        )
                    } else {
                        item
                    }

                suspend fun setIndex(index: Int, value: JSValue) {
                    val key = PropertyKey.Str(index.toString())
                    // 已有不可配置属性：只允许写入相同的可写数据值
                    val existing = array.getOwnProperty(key)
                    if (existing != null && !existing.configurable) {
                        // CreateDataProperty 要求 configurable: true，与不可配置属性冲突
                        interpreter.typeError("Cannot redefine property '$key'")
                    }
                    val defined = array.defineOwnProperty(
                        key,
                        com.muedsa.js.runtime.value.PropertyDescriptor.data(
                            value,
                            writable = true,
                            enumerable = true,
                            configurable = true,
                        ),
                    )
                    if (!defined) interpreter.typeError("Cannot define property '$key'")
                }

                var index = 0
                if (source !is JSUndefined && source !is JSNull) {
                    if (interpreter.isIterable(source)) {
                        // 迭代路径逐项调用 mapfn；mapfn 抛错时关闭迭代器
                        interpreter.consumeIterator(source) { item ->
                            setIndex(index, applyMap(item, index))
                            index++
                        }
                    } else {
                        // 非可迭代对象按 array-like（ToObject + length）处理
                        val objectValue = interpreter.toObject(source)
                        val lengthNumber = interpreter.toNumber(
                            interpreter.getV(objectValue, PropertyKey.LENGTH)
                        )
                        val length = when {
                            lengthNumber.isNaN() || lengthNumber <= 0.0 -> 0
                            lengthNumber >= MAX_ARRAY_LIKE_LENGTH -> MAX_ARRAY_LIKE_LENGTH
                            else -> floor(lengthNumber).toInt()
                        }
                        for (sourceIndex in 0 until length) {
                            val item = interpreter.getV(
                                objectValue,
                                PropertyKey.Str(sourceIndex.toString()),
                            )
                            setIndex(index, applyMap(item, index))
                            index++
                        }
                    }
                }

                // Set(A, "length", k, true)
                interpreter.putV(array, PropertyKey.LENGTH, JSNumber(index.toDouble()), true)
                array
            },
        )
    }

    /** 要求 this 是数组。 */
    /**
     * Array.prototype 方法对 array-like 的快速适配。
     *
     * 真实数组直接返回；其他对象按 length/索引读取为快照。
     * 写方法（push/splice/sort 等）仍只对真实数组生效，后续再引入完整视图。
     */
    private suspend fun requireArray(interpreter: Interpreter, thisValue: JSValue, name: String): JSArray {
        (thisValue as? JSArray)?.let { return it }
        if (thisValue is JSNull || thisValue is JSUndefined) {
            interpreter.typeError("$name called on null or undefined")
        }
        val objectValue = interpreter.toObject(thisValue)
        val lengthValue = interpreter.getV(objectValue, PropertyKey.LENGTH)
        val lengthNumber = interpreter.toNumber(lengthValue)
        val length = when {
            lengthNumber.isNaN() || lengthNumber <= 0.0 -> 0
            lengthNumber >= MAX_ARRAY_LIKE_LENGTH -> MAX_ARRAY_LIKE_LENGTH
            else -> floor(lengthNumber).toInt()
        }
        val elements = ArrayList<JSValue?>(length)
        for (index in 0 until length) {
            val key = PropertyKey.Str(index.toString())
            elements.add(if (objectValue.hasProperty(key)) interpreter.getV(objectValue, key) else null)
        }
        return ArrayLikeSnapshot(
            elements = elements,
            prototype = interpreter.intrinsics.arrayPrototype,
            original = objectValue,
            originalLength = length,
        )
    }

    /** 将 array-like 快照的修改写回原对象。 */
    private suspend fun syncArrayLike(interpreter: Interpreter, array: JSArray) {
        val snapshot = array as? ArrayLikeSnapshot ?: return
        val source = snapshot.original
        for (index in array.length until snapshot.originalLength) {
            val key = PropertyKey.Str(index.toString())
            if (source.hasProperty(key)) source.deleteOwnProperty(key)
        }
        for (index in 0 until array.length) {
            val key = PropertyKey.Str(index.toString())
            val element = array.getElement(index)
            if (element == null) {
                if (source.hasProperty(key)) source.deleteOwnProperty(key)
            } else {
                interpreter.putV(source, key, element, strict = false)
            }
        }
        interpreter.putV(
            source,
            PropertyKey.LENGTH,
            JSNumber(array.length.toDouble()),
            strict = false,
        )
    }

    /** 校验回调参数。 */
    private fun requireCallback(interpreter: Interpreter, callback: JSValue, name: String) {
        if (!interpreter.isCallable(callback)) {
            interpreter.typeError("$name requires a function as the first argument")
        }
    }

    /** 规范 ToObject(this)：null/undefined 抛 TypeError。 */
    private suspend fun requireObject(
        interpreter: Interpreter,
        thisValue: JSValue,
        name: String,
    ): JSObject {
        if (thisValue is JSNull || thisValue is JSUndefined) {
            interpreter.typeError("$name called on null or undefined")
        }
        return interpreter.toObject(thisValue)
    }

    /** 按规范以 ToLength(Get(O, "length")) 读取长度；Infinity 饱和到 2^53-1。 */
    private suspend fun lengthOf(interpreter: Interpreter, objectValue: JSObject): Long {
        val number = interpreter.toNumber(interpreter.getV(objectValue, PropertyKey.LENGTH))
        if (number.isNaN() || number <= 0.0) return 0L
        if (number >= MAX_SAFE_INTEGER.toDouble()) return MAX_SAFE_INTEGER
        return floor(number).toLong()
    }

    /** HasProperty → Get；属性不存在（含空洞）返回 null。 */
    private suspend fun elementAt(
        interpreter: Interpreter,
        objectValue: JSObject,
        index: Long,
    ): JSValue? {
        val key = PropertyKey.Str(index.toString())
        return if (objectValue.hasProperty(key)) interpreter.getV(objectValue, key) else null
    }

    /** ArrayCreate(length)：创建指定长度的稀疏数组（不读取任何用户代码）。 */
    private fun createArrayWithLength(interpreter: Interpreter, length: Long): JSArray {
        if (length < 0 || length > JSArray.MAX_LENGTH) {
            interpreter.rangeError("Invalid array length")
        }
        val array = JSArray(prototype = interpreter.intrinsics.arrayPrototype)
        array.setLength(length)
        return array
    }

    /**
     * ArraySpeciesCreate(originalArray, length)（9.4.2.3）。
     *
     * 非数组直接 ArrayCreate；数组则读取 constructor/@@species；
     * species 为 null/undefined 时回退内建 Array，非法构造器抛 TypeError。
     */
    private suspend fun arraySpeciesCreate(
        interpreter: Interpreter,
        originalArray: JSObject,
        length: Long,
    ): JSObject {
        val normalizedLength = if (length == 0L) 0L else length
        if (originalArray !is JSArray) {
            return createArrayWithLength(interpreter, normalizedLength)
        }
        var constructor = interpreter.getV(originalArray, PropertyKey.CONSTRUCTOR)
        if (constructor is JSObject) {
            constructor = interpreter.getV(
                constructor,
                PropertyKey.Sym(interpreter.intrinsics.symbolSpecies),
            )
            if (constructor is JSNull) constructor = JSUndefined
        }
        if (constructor is JSUndefined) {
            return createArrayWithLength(interpreter, normalizedLength)
        }
        if (!interpreter.isConstructor(constructor)) {
            interpreter.typeError("Species constructor is not a constructor")
        }
        val created = interpreter.construct(
            constructor,
            listOf(JSNumber(normalizedLength.toDouble())),
        )
        return created as? JSObject
            ?: interpreter.typeError("Species constructor did not return an object")
    }

    /** CreateDataPropertyOrThrow：定义失败（不可扩展/不可配置）抛 TypeError。 */
    private suspend fun createDataPropertyOrThrow(
        interpreter: Interpreter,
        target: JSObject,
        index: Long,
        value: JSValue,
    ) {
        val key = PropertyKey.Str(index.toString())
        val existing = target.getOwnProperty(key)
        if (existing != null) {
            // 目标描述符是 {writable:true, enumerable:true, configurable:true}，
            // 只有可配置的现有属性才能被这样覆盖
            if (!existing.configurable) {
                interpreter.typeError("Cannot redefine property: $key")
            }
        } else if (!target.extensible) {
            interpreter.typeError("Cannot define property $index")
        }
        val success = interpreter.defineOwnPropertyOn(
            target,
            key,
            PropertyDescriptor.data(value),
        )
        if (!success) {
            interpreter.typeError("Cannot define property $index")
        }
    }

    /** IsConcatSpreadable：优先读取 @@isConcatSpreadable，否则数组才展开。 */
    private suspend fun isConcatSpreadable(interpreter: Interpreter, value: JSValue): Boolean {
        if (value !is JSObject) return false
        val spreadable = interpreter.getV(
            value,
            PropertyKey.Sym(interpreter.intrinsics.symbolIsConcatSpreadable),
        )
        if (spreadable !is JSUndefined) return interpreter.toBoolean(spreadable)
        return value is JSArray
    }

    /** ToIntegerOrInfinity：向零截断，NaN → 0，超出 Long 范围饱和。 */
    private suspend fun toIntegerOrInfinity(interpreter: Interpreter, value: JSValue): Long {
        val number = interpreter.toNumber(value)
        if (number.isNaN()) return 0L
        if (number >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        if (number <= Long.MIN_VALUE.toDouble()) return Long.MIN_VALUE
        return number.toLong()
    }

    /** ToIntegerOrInfinity(depth)；深度上限收敛到 Int 范围以控制递归。 */
    private suspend fun toFlattenDepth(interpreter: Interpreter, value: JSValue): Long {
        val number = interpreter.toNumber(value)
        if (number.isNaN() || number <= 0.0) return 0L
        if (number >= Int.MAX_VALUE.toDouble()) return Int.MAX_VALUE.toLong()
        return floor(number).toLong()
    }

    /**
     * FlattenIntoArray(target, source, sourceLength, start, depth[, mapper, thisArg])。
     *
     * 返回写入目标后的下一个索引；元素仅在 IsArray 时递归展开。
     */
    private suspend fun flattenIntoArray(
        interpreter: Interpreter,
        target: JSObject,
        source: JSObject,
        sourceLength: Long,
        start: Long,
        depth: Long,
        mapper: JSValue? = null,
        thisArg: JSValue = JSUndefined,
    ): Long {
        var targetIndex = start
        var sourceIndex = 0L
        while (sourceIndex < sourceLength) {
            val key = PropertyKey.Str(sourceIndex.toString())
            if (source.hasProperty(key)) {
                var element = interpreter.getV(source, key)
                if (mapper != null) {
                    element = interpreter.callFunction(
                        mapper,
                        thisArg,
                        listOf(element, JSNumber(sourceIndex.toDouble()), source),
                    )
                }
                if (depth > 0 && element is JSArray) {
                    val elementLength = lengthOf(interpreter, element)
                    targetIndex = flattenIntoArray(
                        interpreter,
                        target,
                        element,
                        elementLength,
                        targetIndex,
                        depth - 1,
                    )
                } else {
                    if (targetIndex >= MAX_SAFE_INTEGER) {
                        interpreter.typeError("Invalid array length")
                    }
                    createDataPropertyOrThrow(interpreter, target, targetIndex, element)
                    targetIndex++
                }
            }
            sourceIndex++
        }
        return targetIndex
    }

    /** 调用迭代回调（第三个参数为原始对象）。 */
    private suspend fun callCallback(
        interpreter: Interpreter,
        callback: JSValue,
        thisArg: JSValue,
        element: JSValue,
        index: Long,
        target: JSObject,
    ): JSValue = interpreter.callFunction(
        callback,
        thisArg,
        listOf(element, JSNumber(index.toDouble()), target),
    )

    /** 把对象按数组语义转换为 join 字符串（null/undefined/空洞输出空串）。 */
    private suspend fun joinElements(
        interpreter: Interpreter,
        objectValue: JSObject,
        length: Long,
        separator: String,
    ): String {
        val parts = ArrayList<String>()
        var index = 0L
        while (index < length) {
            val element = elementAt(interpreter, objectValue, index)
            parts.add(
                if (element == null || element is JSNull || element is JSUndefined) {
                    ""
                } else {
                    interpreter.toStringValue(element)
                }
            )
            index++
        }
        return parts.joinToString(separator)
    }

    /**
     * 排序（阶段 A 使用插入排序以支持 suspend 比较函数）。
     *
     * 空洞会被移到末尾；默认按字符串比较。
     */
    private suspend fun sortElements(
        interpreter: Interpreter,
        array: JSArray,
        comparator: JSValue?,
    ) {
        val values = ArrayList<JSValue>()
        for (index in 0 until array.length) {
            array.getElement(index)?.let { values.add(it) }
        }

        for (index in 1 until values.size) {
            val current = values[index]
            var position = index - 1
            while (position >= 0) {
                val comparison = if (comparator != null && comparator !is JSUndefined && interpreter.isCallable(comparator)) {
                    interpreter.toNumber(
                        interpreter.callFunction(comparator, JSUndefined, listOf(values[position], current))
                    )
                } else {
                    interpreter.toStringValue(values[position])
                        .compareTo(interpreter.toStringValue(current))
                        .toDouble()
                }
                if (comparison > 0.0) {
                    values[position + 1] = values[position]
                    position--
                } else {
                    break
                }
            }
            values[position + 1] = current
        }

        // 排序后数组长度保持不变，原有索引全部清除后重新写入（空洞移到末尾）
        array.clearElements()
        for (index in values.indices) {
            array.setElement(index, values[index])
        }
    }
}
