package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.PromiseSupport
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyDescriptor
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
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
                // 泛型 array-like：按规范使用 ToLength 与 Long 索引
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.push")
                val length = lengthOf(interpreter, objectValue)
                if (length + args.size > MAX_SAFE_INTEGER) {
                    interpreter.typeError("Invalid array length")
                }
                args.forEachIndexed { offset, argument ->
                    interpreter.putV(
                        objectValue,
                        PropertyKey.Str((length + offset).toString()),
                        argument,
                        strict = true,
                    )
                }
                val newLength = length + args.size
                interpreter.putV(
                    objectValue,
                    PropertyKey.LENGTH,
                    JSNumber(newLength.toDouble()),
                    strict = true,
                )
                JSNumber(newLength.toDouble())
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("pop"),
            value = BuiltinHelpers.method(intrinsics, "pop", 0) { interpreter, thisValue, _ ->
                // 规范算法：ToObject + ToLength，Get/Delete/Put 均经过原型链语义
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.pop")
                val length = lengthOf(interpreter, objectValue)
                if (length == 0L) {
                    interpreter.putV(
                        objectValue, PropertyKey.LENGTH, JSNumber(0.0), strict = true,
                    )
                    return@method JSUndefined
                }
                val newLength = length - 1
                val key = PropertyKey.Str(newLength.toString())
                val element = interpreter.getV(objectValue, key)
                deletePropertyOrThrow(interpreter, objectValue, key)
                interpreter.putV(
                    objectValue,
                    PropertyKey.LENGTH,
                    JSNumber(newLength.toDouble()),
                    strict = true,
                )
                element
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("shift"),
            value = BuiltinHelpers.method(intrinsics, "shift", 0) { interpreter, thisValue, _ ->
                // 规范算法：Get(0) 不受空洞影响；搬移时以 HasProperty 决定搬移或删除
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.shift")
                val length = lengthOf(interpreter, objectValue)
                if (length == 0L) {
                    interpreter.putV(
                        objectValue, PropertyKey.LENGTH, JSNumber(0.0), strict = true,
                    )
                    return@method JSUndefined
                }
                val first = interpreter.getV(objectValue, PropertyKey.Str("0"))
                var index = 1L
                while (index < length) {
                    moveIndex(
                        interpreter,
                        objectValue,
                        PropertyKey.Str(index.toString()),
                        PropertyKey.Str((index - 1).toString()),
                    )
                    index++
                }
                deletePropertyOrThrow(
                    interpreter,
                    objectValue,
                    PropertyKey.Str((length - 1).toString()),
                )
                interpreter.putV(
                    objectValue,
                    PropertyKey.LENGTH,
                    JSNumber((length - 1).toDouble()),
                    strict = true,
                )
                first
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("unshift"),
            value = BuiltinHelpers.method(intrinsics, "unshift", 1) { interpreter, thisValue, args ->
                // 规范算法：先整体右移，再写入新元素，最后写 length
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.unshift")
                val length = lengthOf(interpreter, objectValue)
                val count = args.size
                if (count > 0) {
                    if (length + count > MAX_SAFE_INTEGER) {
                        interpreter.typeError("Invalid array length")
                    }
                    var index = length
                    while (index > 0L) {
                        moveIndex(
                            interpreter,
                            objectValue,
                            PropertyKey.Str((index - 1).toString()),
                            PropertyKey.Str((index + count - 1).toString()),
                        )
                        index--
                    }
                    args.forEachIndexed { offset, value ->
                        interpreter.putV(
                            objectValue,
                            PropertyKey.Str(offset.toString()),
                            value,
                            strict = true,
                        )
                    }
                }
                val newLength = length + count
                interpreter.putV(
                    objectValue,
                    PropertyKey.LENGTH,
                    JSNumber(newLength.toDouble()),
                    strict = true,
                )
                JSNumber(newLength.toDouble())
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
                // 显式 undefined 等价于未提供（end 默认 length）
                var end = if (args.size > 1 && args[1] !is JSUndefined) {
                    interpreter.toNumber(args[1]).toLong()
                } else {
                    length
                }
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
                // 规范：len 为 0 时直接返回 -1，不读取 fromIndex
                if (length == 0L) return@method JSNumber(-1.0)
                var fromIndex = if (args.size > 1 && args[1] !is JSUndefined) {
                    toIntegerOrInfinityNumber(interpreter, args[1]).toLong()
                } else {
                    0L
                }
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
                // 规范：len 为 0 时直接返回 -1，不读取 fromIndex
                if (length == 0L) return@method JSNumber(-1.0)
                var fromIndex = if (args.size > 1 && args[1] !is JSUndefined) {
                    toIntegerOrInfinityNumber(interpreter, args[1]).toLong()
                } else {
                    length - 1
                }
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
                // 规范顺序：len 为 0 时不读取 fromIndex
                if (length == 0L) return@method JSBoolean.FALSE
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
                    // 规范使用 Get（空洞视为 undefined）
                    val element = interpreter.getV(objectValue, PropertyKey.Str(index.toString()))
                    if (interpreter.toBoolean(
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
                    // 规范使用 Get（空洞视为 undefined）
                    val element = interpreter.getV(objectValue, PropertyKey.Str(index.toString()))
                    if (interpreter.toBoolean(
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
                // 规范算法：Long 索引，空洞与原型链属性通过 HasProperty/Get 处理
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.reverse")
                val length = lengthOf(interpreter, objectValue)
                var lower = 0L
                while (lower < length / 2) {
                    val upper = length - 1 - lower
                    val lowerKey = PropertyKey.Str(lower.toString())
                    val upperKey = PropertyKey.Str(upper.toString())
                    val lowerExists = objectValue.hasProperty(lowerKey)
                    val lowerValue = if (lowerExists) interpreter.getV(objectValue, lowerKey) else null
                    val upperExists = objectValue.hasProperty(upperKey)
                    val upperValue = if (upperExists) interpreter.getV(objectValue, upperKey) else null
                    if (upperExists) {
                        interpreter.putV(objectValue, lowerKey, upperValue!!, strict = true)
                    } else {
                        deletePropertyOrThrow(interpreter, objectValue, lowerKey)
                    }
                    if (lowerExists) {
                        interpreter.putV(objectValue, upperKey, lowerValue!!, strict = true)
                    } else {
                        deletePropertyOrThrow(interpreter, objectValue, upperKey)
                    }
                    lower++
                }
                objectValue
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("sort"),
            value = BuiltinHelpers.method(intrinsics, "sort", 1) { interpreter, thisValue, args ->
                val comparator = args.getOrNull(0)
                if (comparator != null && comparator !is JSUndefined && !interpreter.isCallable(comparator)) {
                    interpreter.typeError("The comparison function must be either a function or undefined")
                }
                // 规范 Array.prototype.sort：ToObject 后按 HasProperty/Get 收集并写回原对象
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.sort")
                val length = lengthOf(interpreter, objectValue)
                sortElements(interpreter, objectValue, length, comparator)
                objectValue
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("splice"),
            value = BuiltinHelpers.method(intrinsics, "splice", 2) { interpreter, thisValue, args ->
                // 完整规范算法（Long 索引；原型链、空洞与删除失败均按规范处理）
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.splice")
                    val length = lengthOf(interpreter, objectValue)
                    val startNumber = toIntegerOrInfinityNumber(
                        interpreter, args.getOrElse(0) { JSUndefined },
                    )
                    val actualStart = if (startNumber < 0) {
                        kotlin.math.max(length + startNumber, 0.0).toLong()
                    } else {
                        kotlin.math.min(startNumber, length.toDouble()).toLong()
                    }
                    val actualDeleteCount = when {
                        args.isEmpty() -> 0L
                        args.size == 1 -> length - actualStart
                        else -> {
                            val deleteCount = toIntegerOrInfinityNumber(interpreter, args[1])
                            kotlin.math.min(
                                kotlin.math.max(deleteCount, 0.0),
                                (length - actualStart).toDouble(),
                            ).toLong()
                        }
                    }
                    val items = if (args.size > 2) args.subList(2, args.size) else emptyList()
                    val itemCount = items.size.toLong()
                    if (length + itemCount - actualDeleteCount > MAX_SAFE_INTEGER) {
                        interpreter.typeError("Invalid array length")
                    }
                    val removed = arraySpeciesCreate(interpreter, objectValue, actualDeleteCount)
                    var index = 0L
                    while (index < actualDeleteCount) {
                        val from = PropertyKey.Str((actualStart + index).toString())
                        if (objectValue.hasProperty(from)) {
                            createDataPropertyOrThrow(
                                interpreter, removed, index, interpreter.getV(objectValue, from),
                            )
                        }
                        index++
                    }
                    interpreter.putV(
                        removed,
                        PropertyKey.LENGTH,
                        JSNumber(actualDeleteCount.toDouble()),
                        strict = true,
                    )
                    if (itemCount < actualDeleteCount) {
                        var move = actualStart
                        while (move < length - actualDeleteCount) {
                            moveIndex(
                                interpreter,
                                objectValue,
                                PropertyKey.Str((move + actualDeleteCount).toString()),
                                PropertyKey.Str((move + itemCount).toString()),
                            )
                            move++
                        }
                        var tail = length
                        while (tail > length - actualDeleteCount + itemCount) {
                            deletePropertyOrThrow(
                                interpreter, objectValue, PropertyKey.Str((tail - 1).toString()),
                            )
                            tail--
                        }
                    } else if (itemCount > actualDeleteCount) {
                        var move = length - actualDeleteCount
                        while (move > actualStart) {
                            moveIndex(
                                interpreter,
                                objectValue,
                                PropertyKey.Str((move + actualDeleteCount - 1).toString()),
                                PropertyKey.Str((move + itemCount - 1).toString()),
                            )
                            move--
                        }
                    }
                    items.forEachIndexed { offset, value ->
                        interpreter.putV(
                            objectValue,
                            PropertyKey.Str((actualStart + offset).toString()),
                            value,
                            strict = true,
                        )
                    }
                    interpreter.putV(
                        objectValue,
                        PropertyKey.LENGTH,
                        JSNumber((length - actualDeleteCount + itemCount).toDouble()),
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
                sortElements(interpreter, target, length, comparator)
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
                // 规范算法：Get(O, k)，原型链与空洞语义由 Get 决定
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.at")
                val length = lengthOf(interpreter, objectValue)
                val relativeIndex = if (args.isEmpty() || args[0] is JSUndefined) {
                    0.0
                } else {
                    toIntegerOrInfinityNumber(interpreter, args[0])
                }
                val k = if (relativeIndex >= 0) relativeIndex else length + relativeIndex
                if (k < 0.0 || k >= length.toDouble()) {
                    JSUndefined
                } else {
                    interpreter.getV(objectValue, PropertyKey.Str(k.toLong().toString()))
                }
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
                // 规范算法：ToObject + ToLength，start/end 按相对索引归一，返回 this 的包装对象
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.fill")
                val value = args.getOrElse(0) { JSUndefined }
                val length = lengthOf(interpreter, objectValue)
                val start = relativeIndexNumber(
                    if (args.size > 1 && args[1] !is JSUndefined) {
                        toIntegerOrInfinityNumber(interpreter, args[1])
                    } else {
                        0.0
                    },
                    length,
                )
                val end = relativeIndexNumber(
                    if (args.size > 2 && args[2] !is JSUndefined) {
                        toIntegerOrInfinityNumber(interpreter, args[2])
                    } else {
                        length.toDouble()
                    },
                    length,
                )
                var index = start
                while (index < end) {
                    interpreter.putV(
                        objectValue,
                        PropertyKey.Str(index.toString()),
                        value,
                        strict = true,
                    )
                    index++
                }
                objectValue
            },
        )

        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("copyWithin"),
            value = BuiltinHelpers.method(intrinsics, "copyWithin", 2) { interpreter, thisValue, args ->
                // 规范算法：自拷贝按搬移方向处理重叠；返回 ToObject(this)
                val objectValue = requireObject(interpreter, thisValue, "Array.prototype.copyWithin")
                val length = lengthOf(interpreter, objectValue)
                val target = relativeIndexNumber(
                    toIntegerOrInfinityNumber(interpreter, args.getOrElse(0) { JSUndefined }),
                    length,
                )
                val start = relativeIndexNumber(
                    toIntegerOrInfinityNumber(interpreter, args.getOrElse(1) { JSUndefined }),
                    length,
                )
                val end = relativeIndexNumber(
                    if (args.size > 2 && args[2] !is JSUndefined) {
                        toIntegerOrInfinityNumber(interpreter, args[2])
                    } else {
                        length.toDouble()
                    },
                    length,
                )
                var count = minOf(end - start, length - target).coerceAtLeast(0L)
                var from = start
                var to = target
                var direction = 1L
                if (from < to && to < from + count) {
                    from = start + count - 1
                    to = target + count - 1
                    direction = -1L
                }
                while (count > 0L) {
                    moveIndex(
                        interpreter,
                        objectValue,
                        PropertyKey.Str(from.toString()),
                        PropertyKey.Str(to.toString()),
                    )
                    from += direction
                    to += direction
                    count--
                }
                objectValue
            },
        )


        val valuesIterator = BuiltinHelpers.method(intrinsics, "values", 0) { interpreter, thisValue, _ ->
            val target = iteratorTarget(interpreter, thisValue, "Array.prototype.values")
            IteratorBuiltins.createArrayIterator(
                intrinsics,
                target,
                IteratorBuiltins.ArrayIteratorKind.VALUES,
            )
        }
        arrayPrototype.defineDataProperty(PropertyKey.Str("values"), valuesIterator)
        arrayPrototype.defineDataProperty(PropertyKey.Sym(intrinsics.symbolIterator), valuesIterator)
        intrinsics.arrayValuesFunction = valuesIterator
        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("keys"),
            value = BuiltinHelpers.method(intrinsics, "keys", 0) { interpreter, thisValue, _ ->
                val target = iteratorTarget(interpreter, thisValue, "Array.prototype.keys")
                IteratorBuiltins.createArrayIterator(
                    intrinsics,
                    target,
                    IteratorBuiltins.ArrayIteratorKind.KEYS,
                )
            },
        )
        arrayPrototype.defineDataProperty(
            key = PropertyKey.Str("entries"),
            value = BuiltinHelpers.method(intrinsics, "entries", 0) { interpreter, thisValue, _ ->
                val target = iteratorTarget(interpreter, thisValue, "Array.prototype.entries")
                IteratorBuiltins.createArrayIterator(
                    intrinsics,
                    target,
                    IteratorBuiltins.ArrayIteratorKind.ENTRIES,
                )
            },
        )
    }

    /** 数组迭代方法的接收者：任意对象按 array-like 实时读取，不做快照。 */
    private suspend fun iteratorTarget(
        interpreter: Interpreter,
        thisValue: JSValue,
        name: String,
    ): JSObject {
        if (thisValue is JSNull || thisValue is JSUndefined) {
            interpreter.typeError("$name called on null or undefined")
        }
        return interpreter.toObject(thisValue)
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
            value = BuiltinHelpers.method(intrinsics, "of", 0) { interpreter, thisValue, args ->
                val length = args.size
                val array: JSObject = if (interpreter.isConstructor(thisValue)) {
                    interpreter.construct(thisValue, listOf(JSNumber(length.toDouble())))
                        as? JSObject ?: interpreter.typeError("Array.of: constructor result is not an object")
                } else {
                    JSArray(emptyList(), prototype = arrayPrototype)
                }
                for (index in args.indices) {
                    // CreateDataPropertyOrThrow：不可扩展/不可配置属性必须抛 TypeError
                    createDataPropertyOrThrow(interpreter, array, index.toLong(), args[index])
                }
                // Set(A, "length", len, true)：可能命中自定义 length setter
                interpreter.putV(
                    array,
                    PropertyKey.LENGTH,
                    JSNumber(length.toDouble()),
                    strict = true,
                )
                array
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

                // GetMethod(items, @@iterator)：null/undefined 直接抛 TypeError
                if (source is JSNull || source is JSUndefined) {
                    interpreter.typeError("Array.from requires an array-like or iterable object")
                }
                val iterable = interpreter.isIterable(source)

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

                val array: JSObject
                var index = 0
                if (iterable) {
                    // 迭代路径：GetMethod 之后再构造结果对象
                    array = if (interpreter.isConstructor(thisValue)) {
                        interpreter.construct(thisValue, emptyList()) as? JSObject
                            ?: interpreter.typeError("Array.from constructor did not return an object")
                    } else {
                        JSArray(prototype = arrayPrototype)
                    }
                    interpreter.consumeIterator(source) { item ->
                        createDataPropertyOrThrow(
                            interpreter, array, index.toLong(), applyMap(item, index),
                        )
                        index++
                    }
                } else {
                    // array-like：先读取 length，再按长度构造结果对象
                    val objectValue = interpreter.toObject(source)
                    val lengthNumber = interpreter.toNumber(
                        interpreter.getV(objectValue, PropertyKey.LENGTH)
                    )
                    val length = when {
                        lengthNumber.isNaN() || lengthNumber <= 0.0 -> 0
                        lengthNumber >= MAX_ARRAY_LIKE_LENGTH -> MAX_ARRAY_LIKE_LENGTH
                        else -> floor(lengthNumber).toInt()
                    }
                    array = if (interpreter.isConstructor(thisValue)) {
                        interpreter.construct(thisValue, listOf(JSNumber(length.toDouble())))
                            as? JSObject
                            ?: interpreter.typeError("Array.from constructor did not return an object")
                    } else {
                        createArrayWithLength(interpreter, length.toLong())
                    }
                    for (sourceIndex in 0 until length) {
                        val item = interpreter.getV(
                            objectValue,
                            PropertyKey.Str(sourceIndex.toString()),
                        )
                        createDataPropertyOrThrow(
                            interpreter, array, sourceIndex.toLong(), applyMap(item, sourceIndex),
                        )
                        index++
                    }
                }

                // Set(A, "length", k, true)
                interpreter.putV(array, PropertyKey.LENGTH, JSNumber(index.toDouble()), true)
                array
            },
        )

        arrayConstructor.defineDataProperty(
            key = PropertyKey.Str("fromAsync"),
            value = BuiltinHelpers.method(intrinsics, "fromAsync", 1) { interpreter, thisValue, args ->
                arrayFromAsync(
                    interpreter = interpreter,
                    constructor = thisValue,
                    items = args.getOrElse(0) { JSUndefined },
                    mapFunction = args.getOrElse(1) { JSUndefined },
                    thisArg = args.getOrElse(2) { JSUndefined },
                )
            },
        )
    }

    /**
     * Array.fromAsync：立即返回 Promise，并在协程中同步执行到首个 Await，
     * 与 async 函数的执行语义保持一致。
     */
    private suspend fun arrayFromAsync(
        interpreter: Interpreter,
        constructor: JSValue,
        items: JSValue,
        mapFunction: JSValue,
        thisArg: JSValue,
    ): JSValue {
        val promise = JSPromise(interpreter.intrinsics.promisePrototype)
        val (resolveFunction, rejectFunction) =
            PromiseSupport.createResolvingFunctions(interpreter, promise)
        interpreter.context.eventLoop.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = performArrayFromAsync(
                    interpreter = interpreter,
                    constructor = constructor,
                    items = items,
                    mapFunction = mapFunction,
                    thisArg = thisArg,
                )
                interpreter.callFunction(resolveFunction, JSUndefined, listOf(result))
            } catch (error: JSException) {
                interpreter.callFunction(rejectFunction, JSUndefined, listOf(error.value))
            }
        }
        return promise
    }

    /** Array.fromAsync 主体：异步迭代 / 同步迭代 / array-like 三条路径。 */
    private suspend fun performArrayFromAsync(
        interpreter: Interpreter,
        constructor: JSValue,
        items: JSValue,
        mapFunction: JSValue,
        thisArg: JSValue,
    ): JSObject {
        if (items is JSNull || items is JSUndefined) {
            interpreter.typeError("Array.fromAsync requires an iterable or array-like object")
        }
        val mapping = mapFunction !is JSUndefined
        if (mapping && !interpreter.isCallable(mapFunction)) {
            interpreter.typeError("Array.fromAsync: mapfn is not a function")
        }
        val intrinsics = interpreter.intrinsics
        val objectItems = interpreter.toObject(items)
        // 先 GetMethod(@@asyncIterator)，再回退到 @@iterator
        val asyncMethod = getIteratorMethod(
            interpreter, objectItems, PropertyKey.Sym(intrinsics.symbolAsyncIterator),
        )
        val syncMethod = if (asyncMethod == null) {
            getIteratorMethod(
                interpreter, objectItems, PropertyKey.Sym(intrinsics.symbolIterator),
            )
        } else {
            null
        }

        if (asyncMethod != null || syncMethod != null) {
            val usingAsyncIterator = asyncMethod != null
            val array = if (interpreter.isConstructor(constructor)) {
                interpreter.construct(constructor, emptyList()) as? JSObject
                    ?: interpreter.typeError("Array.fromAsync constructor did not return an object")
            } else {
                JSArray(prototype = intrinsics.arrayPrototype)
            }
            val method = asyncMethod ?: syncMethod!!
            val iteratorValue = interpreter.callFunction(method, objectItems, emptyList())
            val iterator = iteratorValue as? JSObject
                ?: interpreter.typeError("Iterator method did not return an object")
            val nextMethod = interpreter.getV(iterator, PropertyKey.Str("next"))
            var index = 0L
            try {
                while (true) {
                    val nextResult = interpreter.callFunction(nextMethod, iterator, emptyList())
                    // 异步迭代器需要 Await(next())；同步迭代器的结果对象已就绪
                    val settled = if (usingAsyncIterator) {
                        awaitJsValue(interpreter, nextResult)
                    } else {
                        nextResult
                    }
                    if (settled !is JSObject) {
                        interpreter.typeError("Iterator result is not an object")
                    }
                    if (interpreter.toBoolean(
                            interpreter.getV(settled, PropertyKey.Str("done")),
                        )
                    ) {
                        break
                    }
                    var value = interpreter.getV(settled, PropertyKey.Str("value"))
                    // 仅同步迭代器（AsyncFromSync）会 await 输入值
                    if (!usingAsyncIterator) {
                        value = awaitJsValue(interpreter, value)
                    }
                    if (mapping) {
                        value = interpreter.callFunction(
                            mapFunction,
                            thisArg,
                            listOf(value, JSNumber(index.toDouble())),
                        )
                        value = awaitJsValue(interpreter, value)
                    }
                    createDataPropertyOrThrow(interpreter, array, index, value)
                    index++
                }
                interpreter.putV(array, PropertyKey.LENGTH, JSNumber(index.toDouble()), true)
                return array
            } catch (error: JSException) {
                closeAsyncIterator(interpreter, iterator)
                throw error
            }
        }

        // array-like 路径：LengthOfArrayLike 先于构造
        val lengthValue = interpreter.getV(objectItems, PropertyKey.LENGTH)
        val lengthNumber = interpreter.toNumber(lengthValue)
        val length = when {
            lengthNumber.isNaN() || lengthNumber <= 0.0 -> 0L
            lengthNumber >= MAX_SAFE_INTEGER.toDouble() -> MAX_SAFE_INTEGER
            else -> floor(lengthNumber).toLong()
        }
        val array: JSObject = if (interpreter.isConstructor(constructor)) {
            interpreter.construct(constructor, listOf(JSNumber(length.toDouble()))) as? JSObject
                ?: interpreter.typeError("Array.fromAsync constructor did not return an object")
        } else {
            if (length > 4294967295L) {
                interpreter.rangeError("Invalid array length")
            }
            JSArray(prototype = intrinsics.arrayPrototype).also { it.defineLength(length, true) }
        }
        var index = 0L
        while (index < length) {
            var value = interpreter.getV(objectItems, PropertyKey.Str(index.toString()))
            value = awaitJsValue(interpreter, value)
            if (mapping) {
                value = interpreter.callFunction(
                    mapFunction,
                    thisArg,
                    listOf(value, JSNumber(index.toDouble())),
                )
                value = awaitJsValue(interpreter, value)
            }
            createDataPropertyOrThrow(interpreter, array, index, value)
            index++
        }
        interpreter.putV(array, PropertyKey.LENGTH, JSNumber(length.toDouble()), true)
        return array
    }

    /** GetMethod：null/undefined 返回 null，非可调用抛 TypeError。 */
    private suspend fun getIteratorMethod(
        interpreter: Interpreter,
        objectValue: JSObject,
        key: PropertyKey,
    ): JSValue? {
        val method = interpreter.getV(objectValue, key)
        if (method is JSUndefined || method is JSNull) return null
        if (!interpreter.isCallable(method)) {
            interpreter.typeError("Property '${key}' is not a function")
        }
        return method
    }

    /** Await：把非 Promise 值经 PromiseResolve 采纳，并至少让出一个微任务周期。 */
    private suspend fun awaitJsValue(interpreter: Interpreter, value: JSValue): JSValue {
        val promise = if (value is JSPromise) {
            value
        } else {
            val created = JSPromise(interpreter.intrinsics.promisePrototype)
            PromiseSupport.resolve(interpreter, created, value)
            created
        }
        val result = try {
            promise.awaitResult()
        } catch (error: JSException) {
            interpreter.context.eventLoop.awaitMicrotask()
            throw error
        }
        interpreter.context.eventLoop.awaitMicrotask()
        return result
    }

    /** AsyncIteratorClose（throw 完成）：关闭错误被忽略，保留原始异常。 */
    private suspend fun closeAsyncIterator(interpreter: Interpreter, iterator: JSObject) {
        val returnMethod = try {
            interpreter.getV(iterator, PropertyKey.Str("return"))
        } catch (_: JSException) {
            return
        }
        if (returnMethod is JSUndefined || returnMethod is JSNull) return
        if (!interpreter.isCallable(returnMethod)) return
        try {
            interpreter.callFunction(returnMethod, iterator, emptyList())
        } catch (_: JSException) {
            // 保留原始错误
        }
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

    /** DeletePropertyOrThrow：Delete 返回 false 时抛 TypeError。 */
    private fun deletePropertyOrThrow(
        interpreter: Interpreter,
        objectValue: JSObject,
        key: PropertyKey,
    ) {
        if (!objectValue.deleteOwnProperty(key)) {
            interpreter.typeError("Cannot delete property '${key}'")
        }
    }

    /** 规范化搬移：源存在则 Get/Set，否则删除目标属性。 */
    private suspend fun moveIndex(
        interpreter: Interpreter,
        objectValue: JSObject,
        from: PropertyKey,
        to: PropertyKey,
    ) {
        if (objectValue.hasProperty(from)) {
            interpreter.putV(objectValue, to, interpreter.getV(objectValue, from), strict = true)
        } else {
            deletePropertyOrThrow(interpreter, objectValue, to)
        }
    }

    /** ToIntegerOrInfinity（保留 ±Infinity 的 Double 版本）。 */
    private suspend fun toIntegerOrInfinityNumber(interpreter: Interpreter, value: JSValue): Double {
        val number = interpreter.toNumber(value)
        if (number.isNaN()) return 0.0
        if (!number.isFinite()) return number
        return kotlin.math.truncate(number)
    }

    /** 相对索引归一（fill/copyWithin）：负值加 length，再 clamp 到 [0, length]。 */
    private fun relativeIndexNumber(number: Double, length: Long): Long = when {
        number.isNaN() -> 0L
        number < 0.0 -> kotlin.math.max(length + number, 0.0).toLong()
        else -> kotlin.math.min(number, length.toDouble()).toLong()
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
     * 规范 Array.prototype.sort：SortIndexedProperties + 顺序写回。
     *
     * 按 HasProperty/Get 收集现有元素（含原型链，跳过空洞），
     * 使用稳定插入排序执行 SortCompare（支持 suspend 比较函数），
     * 然后逐个 Set 写回并删除剩余索引；数组长度由元素写入自然维持。
     */
    private suspend fun sortElements(
        interpreter: Interpreter,
        objectValue: JSObject,
        length: Long,
        comparator: JSValue?,
    ) {
        val values = ArrayList<JSValue>()
        var index = 0L
        while (index < length) {
            val key = PropertyKey.Str(index.toString())
            if (objectValue.hasProperty(key)) {
                values.add(interpreter.getV(objectValue, key))
            }
            index++
        }

        for (position in 1 until values.size) {
            val current = values[position]
            var cursor = position - 1
            while (cursor >= 0 &&
                sortCompare(interpreter, values[cursor], current, comparator) > 0.0
            ) {
                values[cursor + 1] = values[cursor]
                cursor--
            }
            values[cursor + 1] = current
        }

        for (position in values.indices) {
            interpreter.putV(
                objectValue,
                PropertyKey.Str(position.toString()),
                values[position],
                strict = true,
            )
        }
        var position = values.size.toLong()
        while (position < length) {
            deletePropertyOrThrow(interpreter, objectValue, PropertyKey.Str(position.toString()))
            position++
        }
    }

    /** 规范 SortCompare：undefined 排在末尾，比较函数返回 NaN 视为相等。 */
    private suspend fun sortCompare(
        interpreter: Interpreter,
        x: JSValue,
        y: JSValue,
        comparator: JSValue?,
    ): Double {
        val xIsUndefined = x is JSUndefined
        val yIsUndefined = y is JSUndefined
        if (xIsUndefined && yIsUndefined) return 0.0
        if (xIsUndefined) return 1.0
        if (yIsUndefined) return -1.0
        if (comparator != null && comparator !is JSUndefined) {
            val result = interpreter.toNumber(
                interpreter.callFunction(comparator, JSUndefined, listOf(x, y))
            )
            return if (result.isNaN()) 0.0 else result
        }
        return interpreter.toStringValue(x).compareTo(interpreter.toStringValue(y)).toDouble()
    }
}
