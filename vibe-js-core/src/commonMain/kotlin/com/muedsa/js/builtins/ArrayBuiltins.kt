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
import com.muedsa.js.runtime.value.PropertyKey
import kotlin.math.floor

/**
 * Array 构造器与 Array.prototype。
 *
 * 阶段 A 覆盖常用的构造、迭代、查找、增删与排序方法；
 * 迭代方法遵循"跳过空洞"的规范行为。
 */
object ArrayBuiltins {

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
                lengthValue != floor(lengthValue) || lengthValue > Int.MAX_VALUE.toDouble()
            ) {
                interpreter.rangeError("Invalid array length")
            }
            val array = JSArray(prototype = arrayPrototype)
            array.setLength(lengthValue.toInt())
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
                val array = requireArray(interpreter, thisValue, "Array.prototype.push")
                array.elements.addAll(args)
                JSNumber(array.length.toDouble())
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("pop"),
            value = BuiltinHelpers.method(intrinsics, "pop", 0) { interpreter, thisValue, _ ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.pop")
                if (array.length == 0) JSUndefined else array.elements.removeAt(array.length - 1) ?: JSUndefined
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("shift"),
            value = BuiltinHelpers.method(intrinsics, "shift", 0) { interpreter, thisValue, _ ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.shift")
                if (array.length == 0) JSUndefined else array.elements.removeAt(0) ?: JSUndefined
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("unshift"),
            value = BuiltinHelpers.method(intrinsics, "unshift", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.unshift")
                array.elements.addAll(0, args)
                JSNumber(array.length.toDouble())
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("join"),
            value = BuiltinHelpers.method(intrinsics, "join", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.join")
                val separator = when (val value = args.getOrNull(0)) {
                    null, is JSUndefined -> ","
                    else -> interpreter.toStringValue(value)
                }
                JSString(joinElements(interpreter, array, separator))
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            value = BuiltinHelpers.method(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.toString")
                JSString(joinElements(interpreter, array, ","))
            },
        )

        arrayPrototype.getOwnDataValue(PropertyKey.Str("toString"))?.let { toStringFunction ->
            arrayPrototype.defineDataProperty(
                key = PropertyKey.Str("toLocaleString"),
                value = toStringFunction,
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("slice"),
            value = BuiltinHelpers.method(intrinsics, "slice", 2) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.slice")
                val length = array.length
                var start = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                var end = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: length
                if (start < 0) start += length
                if (start < 0) start = 0
                if (start > length) start = length
                if (end < 0) end += length
                if (end < 0) end = 0
                if (end > length) end = length
                if (end < start) end = start

                val result = ArrayList<JSValue?>(end - start)
                for (index in start until end) {
                    result.add(array.getElement(index))
                }
                JSArray(result, prototype = intrinsics.arrayPrototype)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("indexOf"),
            value = BuiltinHelpers.method(intrinsics, "indexOf", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.indexOf")
                val searchElement = args.getOrNull(0) ?: JSUndefined
                var fromIndex = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: 0
                if (fromIndex < 0) fromIndex += array.length
                if (fromIndex < 0) fromIndex = 0

                var result = -1
                for (index in fromIndex until array.length) {
                    val element = array.getElement(index) ?: continue
                    if (interpreter.strictEquals(element, searchElement)) {
                        result = index
                        break
                    }
                }
                JSNumber(result.toDouble())
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("includes"),
            value = BuiltinHelpers.method(intrinsics, "includes", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.includes")
                val searchElement = args.getOrNull(0) ?: JSUndefined
                var fromIndex = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: 0
                if (fromIndex < 0) fromIndex += array.length
                if (fromIndex < 0) fromIndex = 0

                var result = false
                for (index in fromIndex until array.length) {
                    val element = array.getElement(index) ?: continue
                    if (interpreter.conversions.sameValueZero(element, searchElement)) {
                        result = true
                        break
                    }
                }
                JSBoolean.getJsBoolean(result)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("forEach"),
            value = BuiltinHelpers.method(intrinsics, "forEach", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.forEach")
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                requireCallback(interpreter, callback, "Array.prototype.forEach")
                for (index in 0 until array.length) {
                    val element = array.getElement(index) ?: continue
                    callCallback(interpreter, callback, thisArg, element, index, array)
                }
                JSUndefined
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("map"),
            value = BuiltinHelpers.method(intrinsics, "map", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.map")
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                requireCallback(interpreter, callback, "Array.prototype.map")
                val result = ArrayList<JSValue?>(array.length)
                repeat(array.length) { result.add(null) }
                for (index in 0 until array.length) {
                    val element = array.getElement(index) ?: continue
                    result[index] = callCallback(interpreter, callback, thisArg, element, index, array)
                }
                JSArray(result, prototype = intrinsics.arrayPrototype)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("filter"),
            value = BuiltinHelpers.method(intrinsics, "filter", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.filter")
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                requireCallback(interpreter, callback, "Array.prototype.filter")
                val result = ArrayList<JSValue?>()
                for (index in 0 until array.length) {
                    val element = array.getElement(index) ?: continue
                    val keep = callCallback(interpreter, callback, thisArg, element, index, array)
                    if (interpreter.toBoolean(keep)) result.add(element)
                }
                JSArray(result, prototype = intrinsics.arrayPrototype)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("every"),
            value = BuiltinHelpers.method(intrinsics, "every", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.every")
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                requireCallback(interpreter, callback, "Array.prototype.every")
                var result = true
                for (index in 0 until array.length) {
                    val element = array.getElement(index) ?: continue
                    if (!interpreter.toBoolean(callCallback(interpreter, callback, thisArg, element, index, array))) {
                        result = false
                        break
                    }
                }
                JSBoolean.getJsBoolean(result)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("some"),
            value = BuiltinHelpers.method(intrinsics, "some", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.some")
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                requireCallback(interpreter, callback, "Array.prototype.some")
                var result = false
                for (index in 0 until array.length) {
                    val element = array.getElement(index) ?: continue
                    if (interpreter.toBoolean(callCallback(interpreter, callback, thisArg, element, index, array))) {
                        result = true
                        break
                    }
                }
                JSBoolean.getJsBoolean(result)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("find"),
            value = BuiltinHelpers.method(intrinsics, "find", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.find")
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                requireCallback(interpreter, callback, "Array.prototype.find")
                for (index in 0 until array.length) {
                    val element = array.getElement(index) ?: continue
                    if (interpreter.toBoolean(callCallback(interpreter, callback, thisArg, element, index, array))) {
                        return@method element
                    }
                }
                JSUndefined
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("findIndex"),
            value = BuiltinHelpers.method(intrinsics, "findIndex", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.findIndex")
                val callback = args.getOrNull(0) ?: JSUndefined
                val thisArg = args.getOrNull(1) ?: JSUndefined
                requireCallback(interpreter, callback, "Array.prototype.findIndex")
                for (index in 0 until array.length) {
                    val element = array.getElement(index) ?: continue
                    if (interpreter.toBoolean(callCallback(interpreter, callback, thisArg, element, index, array))) {
                        return@method JSNumber(index.toDouble())
                    }
                }
                JSNumber(-1.0)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("reduce"),
            value = BuiltinHelpers.method(intrinsics, "reduce", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.reduce")
                val callback = args.getOrNull(0) ?: JSUndefined
                requireCallback(interpreter, callback, "Array.prototype.reduce")

                var accumulator: JSValue
                var startIndex: Int
                if (args.size >= 2) {
                    accumulator = args[1]
                    startIndex = 0
                } else {
                    var initialIndex = -1
                    for (index in 0 until array.length) {
                        if (array.hasElement(index)) {
                            initialIndex = index
                            break
                        }
                    }
                    if (initialIndex < 0) {
                        interpreter.typeError("Reduce of empty array with no initial value")
                    }
                    accumulator = array.getElement(initialIndex) ?: JSUndefined
                    startIndex = initialIndex + 1
                }

                for (index in startIndex until array.length) {
                    val element = array.getElement(index) ?: continue
                    accumulator = interpreter.callFunction(
                        callback,
                        JSUndefined,
                        listOf(accumulator, element, JSNumber(index.toDouble()), array),
                    )
                }
                accumulator
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("reduceRight"),
            value = BuiltinHelpers.method(intrinsics, "reduceRight", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.reduceRight")
                val callback = args.getOrNull(0) ?: JSUndefined
                requireCallback(interpreter, callback, "Array.prototype.reduceRight")

                var accumulator: JSValue
                var startIndex: Int
                if (args.size >= 2) {
                    accumulator = args[1]
                    startIndex = array.length - 1
                } else {
                    var initialIndex = -1
                    for (index in array.length - 1 downTo 0) {
                        if (array.hasElement(index)) {
                            initialIndex = index
                            break
                        }
                    }
                    if (initialIndex < 0) {
                        interpreter.typeError("Reduce of empty array with no initial value")
                    }
                    accumulator = array.getElement(initialIndex) ?: JSUndefined
                    startIndex = initialIndex - 1
                }

                for (index in startIndex downTo 0) {
                    val element = array.getElement(index) ?: continue
                    accumulator = interpreter.callFunction(
                        callback,
                        JSUndefined,
                        listOf(accumulator, element, JSNumber(index.toDouble()), array),
                    )
                }
                accumulator
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("concat"),
            value = BuiltinHelpers.method(intrinsics, "concat", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.concat")
                val result = ArrayList<JSValue?>()
                result.addAll(array.elements)
                for (argument in args) {
                    if (argument is JSArray) {
                        result.addAll(argument.elements)
                    } else {
                        result.add(argument)
                    }
                }
                JSArray(result, prototype = intrinsics.arrayPrototype)
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("reverse"),
            value = BuiltinHelpers.method(intrinsics, "reverse", 0) { interpreter, thisValue, _ ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.reverse")
                array.elements.reverse()
                array
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("sort"),
            value = BuiltinHelpers.method(intrinsics, "sort", 1) { interpreter, thisValue, args ->
                val array = requireArray(interpreter, thisValue, "Array.prototype.sort")
                val comparator = args.getOrNull(0)
                if (comparator != null && comparator !is JSUndefined && !interpreter.isCallable(comparator)) {
                    interpreter.typeError("The comparison function must be either a function or undefined")
                }
                sortElements(interpreter, array, comparator)
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

                val removed = ArrayList<JSValue?>(deleteCount)
                repeat(deleteCount) {
                    removed.add(array.elements.removeAt(start))
                }

                val items = if (args.size > 2) args.subList(2, args.size) else emptyList()
                array.elements.addAll(start, items)

                JSArray(removed, prototype = intrinsics.arrayPrototype)
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
                val source = array.elements.subList(start, start + count).toList()
                for (index in 0 until count) {
                    array.elements[target + index] = source[index]
                }
                array
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("Array"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        val valuesIterator = BuiltinHelpers.method(intrinsics, "values", 0) { interpreter, thisValue, _ ->
            val array = requireArray(interpreter, thisValue, "Array.prototype.values")
            IteratorBuiltins.createIterator(
                intrinsics,
                interpreter.iterateValues(array),
                "Array Iterator",
            )
        }
        arrayPrototype.defineDataProperty(PropertyKey.Str("values"), valuesIterator)
        arrayPrototype.defineDataProperty(PropertyKey.Sym(intrinsics.symbolIterator), valuesIterator)
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
            value = BuiltinHelpers.method(intrinsics, "from", 1) { interpreter, _, args ->
                val source = args.getOrNull(0) ?: JSUndefined
                val mapFunction = args.getOrNull(1)
                val values = ArrayList<JSValue?>()

                if (source !is JSUndefined && source !is JSNull) {
                    values.addAll(interpreter.iterateValues(source))
                }

                if (mapFunction != null && interpreter.isCallable(mapFunction)) {
                    for (index in values.indices) {
                        values[index] = interpreter.callFunction(
                            mapFunction,
                            JSUndefined,
                            listOf(values[index] ?: JSUndefined, JSNumber(index.toDouble())),
                        )
                    }
                }

                JSArray(values, prototype = arrayPrototype)
            },
        )
    }

    /** 要求 this 是数组。 */
    private fun requireArray(interpreter: Interpreter, thisValue: JSValue, name: String): JSArray =
        thisValue as? JSArray ?: interpreter.typeError("$name requires that 'this' be an Array")

    /** 校验回调参数。 */
    private fun requireCallback(interpreter: Interpreter, callback: JSValue, name: String) {
        if (!interpreter.isCallable(callback)) {
            interpreter.typeError("$name requires a function as the first argument")
        }
    }

    /** 调用迭代回调。 */
    private suspend fun callCallback(
        interpreter: Interpreter,
        callback: JSValue,
        thisArg: JSValue,
        element: JSValue,
        index: Int,
        array: JSArray,
    ): JSValue = interpreter.callFunction(
        callback,
        thisArg,
        listOf(element, JSNumber(index.toDouble()), array),
    )

    /** 把数组转换为 join 字符串（null/undefined/空洞输出空串）。 */
    private suspend fun joinElements(interpreter: Interpreter, array: JSArray, separator: String): String {
        val parts = ArrayList<String>(array.length)
        for (index in 0 until array.length) {
            val element = array.getElement(index)
            parts.add(
                if (element == null || element is JSNull || element is JSUndefined) {
                    ""
                } else {
                    interpreter.toStringValue(element)
                }
            )
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

        for (index in 0 until array.length) {
            array.elements[index] = null
        }
        for (index in values.indices) {
            array.elements[index] = values[index]
        }
    }
}
