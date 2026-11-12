package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArrayBuffer
import com.muedsa.js.runtime.value.JSBigInt
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSTypedArray
import com.muedsa.js.runtime.value.JSTypedArrayType
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey
import kotlin.math.min
import kotlin.math.truncate

/**
 * %TypedArray% 抽象基类、11 个 TypedArray 构造器与 %TypedArray%.prototype。
 *
 * 结构（与规范一致）：
 * - `%TypedArray%` 本身不是全局对象，只能通过 `Object.getPrototypeOf(Int8Array)` 取得；
 *   它不能被调用或构造，只提供 `@@species` 与全部原型方法；
 * - 11 个构造器的 [[Prototype]] 都是 `%TypedArray%`，各自的 `.prototype`
 *   以 `%TypedArray%.prototype` 为 [[Prototype]]；
 * - 实例的元素读写走 [JSTypedArray] 的整数索引异质方法，其余属性走普通属性表。
 *
 * 构造函数的四种签名：
 * `new T(length)`、`new T(typedArray)`、`new T(arrayBuffer, byteOffset?, length?)`、
 * `new T(iterable | arrayLike)`；species 用于 map/filter/slice/subarray。
 *
 * BigInt64Array/BigUint64Array 的元素是 BigInt：构造/写入时按 ToBigInt 转换，
 * 读取返回 JSBigInt；Number 元素按规范抛 TypeError。
 */
internal object TypedArrayBuiltins {

    /** 创建 11 个构造器、它们各自的原型与全局绑定。 */
    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val basePrototype = intrinsics.typedArrayPrototype

        // %TypedArray%：抽象构造器，[[Prototype]] 是 %Function.prototype%
        val baseConstructor = JSNativeFunction(
            name = "TypedArray",
            length = 0,
            prototype = intrinsics.functionPrototype,
            functionPrototypeObject = basePrototype,
            constructFunction = { interpreter, _, _ ->
                interpreter.typeError("Abstract class TypedArray not directly constructable")
            },
            function = { interpreter, _, _ ->
                interpreter.typeError("Abstract class TypedArray not directly constructable")
            },
        )
        intrinsics.typedArrayConstructor = baseConstructor

        // %TypedArray%[@@species]：getter 返回 this（子类构造器各自继承）
        baseConstructor.defineAccessorProperty(
            key = PropertyKey.Sym(intrinsics.symbolSpecies),
            get = BuiltinHelpers.method(intrinsics, "get [Symbol.species]", 0) { _, thisValue, _ ->
                thisValue
            },
            set = null,
            enumerable = false,
            configurable = true,
        )

        // 11 个具体构造器：[[Prototype]] 是 %TypedArray%，.prototype 是各自类型原型
        val constructors = LinkedHashMap<JSTypedArrayType, JSNativeFunction>()
        for (type in JSTypedArrayType.entries) {
            val prototype = intrinsics.typedArrayPrototypes.getValue(type)
            val constructor = JSNativeFunction(
                name = type.typeName,
                length = 3,
                prototype = baseConstructor,
                functionPrototypeObject = prototype,
                constructFunction = { interpreter, args, newTarget ->
                    constructTypedArray(interpreter, type, args, newTarget)
                },
                function = { interpreter, _, _ ->
                    interpreter.typeError("Constructor ${type.typeName} requires 'new'")
                },
            )
            // 规范：构造器与原型上的 BYTES_PER_ELEMENT 都是不可写/不可枚举/不可配置
            constructor.defineDataProperty(
                key = BYTES_PER_ELEMENT,
                value = JSNumber(type.bytesPerElement.toDouble()),
                writable = false,
                enumerable = false,
                configurable = false,
            )
            prototype.defineDataProperty(
                key = BYTES_PER_ELEMENT,
                value = JSNumber(type.bytesPerElement.toDouble()),
                writable = false,
                enumerable = false,
                configurable = false,
            )
            constructors[type] = constructor
            registry.defineGlobal(type.typeName, constructor)
        }
        intrinsics.typedArrayConstructors = constructors

        Uint8ArrayBase64Builtins.install(
            intrinsics = intrinsics,
            constructor = constructors.getValue(JSTypedArrayType.UINT8),
            prototype = intrinsics.typedArrayPrototypes.getValue(JSTypedArrayType.UINT8),
        )

        installAccessors(intrinsics)
        installMethods(intrinsics)
        installStaticMethods(intrinsics)
    }

    // ================================================================
    // %TypedArray% 的静态方法（11 个构造器继承）
    // ================================================================

    /** 安装 %TypedArray%.from 与 %TypedArray%.of。 */
    private fun installStaticMethods(intrinsics: com.muedsa.js.runtime.Intrinsics) {
        val constructor = intrinsics.typedArrayConstructor

        /** 定义构造器上的静态方法（writable/enumerable=false/configurable=true）。 */
        fun defineStaticMethod(
            name: String,
            length: Int,
            body: suspend (Interpreter, JSValue, List<JSValue>) -> JSValue,
        ) {
            constructor.defineDataProperty(
                key = PropertyKey.Str(name),
                value = BuiltinHelpers.method(intrinsics, name, length, body),
            )
        }

        // %TypedArray%.from(source, mapfn?, thisArg?)：优先迭代器，其次类数组
        defineStaticMethod("from", 1) { interpreter, thisValue, args ->
            if (!interpreter.isConstructor(thisValue)) {
                interpreter.typeError("%TypedArray%.from requires that 'this' be a constructor")
            }
            val source = args.getOrElse(0) { JSUndefined }
            val mapFunction = args.getOrNull(1)?.takeUnless { it is JSUndefined }
            if (mapFunction != null && !interpreter.isCallable(mapFunction)) {
                interpreter.typeError("The map function must be callable")
            }
            val thisArg = args.getOrElse(2) { JSUndefined }
            // GetMethod(source, @@iterator)：null/undefined 由 getV 抛 TypeError
            val iteratorMethod = interpreter.getV(
                source,
                PropertyKey.Sym(intrinsics.symbolIterator),
            )
            if (iteratorMethod !is JSUndefined && iteratorMethod !is JSNull) {
                if (!interpreter.isCallable(iteratorMethod)) {
                    interpreter.typeError("@@iterator is not a function")
                }
                val values = ArrayList<JSValue>()
                interpreter.consumeIterator(source, iteratorMethod) { values.add(it) }
                val result = typedArrayCreateFromConstructor(
                    interpreter,
                    thisValue,
                    values.size,
                )
                values.forEachIndexed { index, value ->
                    interpreter.checkExecution()
                    val mapped = if (mapFunction != null) {
                        interpreter.callFunction(
                            mapFunction,
                            thisArg,
                            listOf(value, JSNumber(index.toDouble())),
                        )
                    } else {
                        value
                    }
                    interpreter.setTypedArrayElement(result, index, mapped)
                }
                result
            } else {
                val arrayLike = interpreter.toObject(source)
                val length = toLength(
                    interpreter,
                    interpreter.getV(arrayLike, PropertyKey.LENGTH),
                )
                if (length > Int.MAX_VALUE.toLong()) {
                    interpreter.rangeError("Invalid typed array length")
                }
                val result = typedArrayCreateFromConstructor(
                    interpreter,
                    thisValue,
                    length.toInt(),
                )
                var index = 0L
                while (index < length) {
                    interpreter.checkExecution()
                    val value = interpreter.getV(
                        arrayLike,
                        PropertyKey.Str(index.toString()),
                    )
                    val mapped = if (mapFunction != null) {
                        interpreter.callFunction(
                            mapFunction,
                            thisArg,
                            listOf(value, JSNumber(index.toDouble())),
                        )
                    } else {
                        value
                    }
                    interpreter.setTypedArrayElement(result, index.toInt(), mapped)
                    index++
                }
                result
            }
        }

        // %TypedArray%.of(...items)：按实参个数构造并逐个写入
        defineStaticMethod("of", 0) { interpreter, thisValue, args ->
            if (!interpreter.isConstructor(thisValue)) {
                interpreter.typeError("%TypedArray%.of requires that 'this' be a constructor")
            }
            val result = typedArrayCreateFromConstructor(interpreter, thisValue, args.size)
            for (index in args.indices) {
                interpreter.setTypedArrayElement(result, index, args[index])
            }
            result
        }
    }

    /**
     * TypedArrayCreateFromConstructor：按构造器与长度创建结果并校验。
     *
     * 规范要求结果是 TypedArray，且构造时只传入一个长度参数时不能短于请求长度。
     */
    private suspend fun typedArrayCreateFromConstructor(
        interpreter: Interpreter,
        constructor: JSValue,
        length: Int,
    ): JSTypedArray {
        val created = interpreter.construct(
            constructor,
            listOf(JSNumber(length.toDouble())),
        )
        val result = created as? JSTypedArray
            ?: interpreter.typeError("Constructor did not return a TypedArray")
        if (result.buffer.isImmutable) {
            interpreter.typeError("Constructor returned a TypedArray backed by an immutable ArrayBuffer")
        }
        if (result.length < length) {
            interpreter.typeError("Constructor returned a too-small TypedArray")
        }
        return result
    }

    // ================================================================
    // 访问器
    // ================================================================

    /** 安装 buffer / byteLength / byteOffset / length 与 @@toStringTag。 */
    private fun installAccessors(intrinsics: com.muedsa.js.runtime.Intrinsics) {
        val prototype = intrinsics.typedArrayPrototype

        /** 定义只读访问器；getter 统一完成品牌检查。 */
        fun defineGetter(name: String, read: (JSTypedArray) -> JSValue) {
            prototype.defineAccessorProperty(
                key = PropertyKey.Str(name),
                get = BuiltinHelpers.method(intrinsics, "get $name", 0) { interpreter, thisValue, _ ->
                    val array = requireTypedArray(
                        interpreter,
                        thisValue,
                        "get TypedArray.prototype.$name",
                    )
                    read(array)
                },
                set = null,
                enumerable = false,
                configurable = true,
            )
        }
        defineGetter("buffer") { it.buffer }
        // 规范：分离后 TypedArrayLength / TypedArrayByteLength 为 0，byteOffset 按
        // IsTypedArrayOutOfBounds 分支返回 0（底层的原始长度/偏移仅在内部算法中使用）
        defineGetter("byteLength") {
            JSNumber(if (it.isDetached) 0.0 else it.byteLength.toDouble())
        }
        defineGetter("byteOffset") {
            JSNumber(if (it.isDetached) 0.0 else it.byteOffset.toDouble())
        }
        defineGetter("length") { JSNumber(if (it.isDetached) 0.0 else it.length.toDouble()) }

        // 规范：%TypedArray%.prototype[@@toStringTag] 是 getter，
        // 只有 this 具有 [[TypedArrayName]] 内部槽时才返回类型名（各类型原型上不再单独定义）
        prototype.defineAccessorProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            get = BuiltinHelpers.method(intrinsics, "get [Symbol.toStringTag]", 0) { _, thisValue, _ ->
                if (thisValue is JSTypedArray) JSString(thisValue.type.typeName) else JSUndefined
            },
            set = null,
            enumerable = false,
            configurable = true,
        )
    }

    // ================================================================
    // 原型方法
    // ================================================================

    /** 安装 %TypedArray%.prototype 上的全部方法。 */
    private fun installMethods(intrinsics: com.muedsa.js.runtime.Intrinsics) {
        val prototype = intrinsics.typedArrayPrototype

        /** 定义方法（writable/enumerable=false/configurable=true）。 */
        fun defineMethod(
            name: String,
            length: Int,
            body: suspend (Interpreter, JSValue, List<JSValue>) -> JSValue,
        ) {
            prototype.defineDataProperty(
                key = PropertyKey.Str(name),
                value = BuiltinHelpers.method(intrinsics, name, length, body),
            )
        }

        defineMethod("set", 1) { interpreter, thisValue, args ->
            val target = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.set")
            requireAttached(interpreter, target, "TypedArray.prototype.set")
            setFromSource(
                interpreter = interpreter,
                target = target,
                source = args.getOrElse(0) { JSUndefined },
                offset = args.getOrElse(1) { JSUndefined },
            )
            JSUndefined
        }

        defineMethod("subarray", 2) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.subarray")
            // 规范不在入口处检查分离：begin/end 的 ToInteger 必须先执行，
            // 分离缓冲区最终由 TypedArraySpeciesCreate 的默认构造器抛 TypeError。
            subarray(interpreter, array, args)
        }

        defineMethod("slice", 2) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.slice")
            requireAttached(interpreter, array, "TypedArray.prototype.slice")
            val len = array.length
            val start = relativeIndex(interpreter, args.getOrElse(0) { JSUndefined }, len)
            val endArgument = args.getOrNull(1)
            val end = if (endArgument == null || endArgument is JSUndefined) {
                len
            } else {
                relativeIndex(interpreter, endArgument, len)
            }
            val count = (end - start).coerceAtLeast(0)
            val result = speciesCreate(interpreter, array, count)
            // 规范：SpeciesCreate 可能执行任意 JS（例如在 @@species 构造器里分离源缓冲区），
            // 拷贝前必须重新确认源缓冲区仍然可用；count 为 0 时无需拷贝，不抛错。
            if (count > 0 && array.isDetached) {
                interpreter.typeError("TypedArray.prototype.slice called on a detached TypedArray")
            }
            if (count > 0 && result.type == array.type) {
                array.copyRawToForward(result, sourceStart = start, targetStart = 0, count = count)
                return@defineMethod result
            }
            var targetIndex = 0
            var sourceIndex = start
            while (sourceIndex < end) {
                interpreter.checkExecution()
                interpreter.setTypedArrayElement(result, targetIndex, array.getElement(sourceIndex))
                targetIndex++
                sourceIndex++
            }
            result
        }

        defineMethod("fill", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.fill")
            requireAttached(interpreter, array, "TypedArray.prototype.fill")
            if (array.buffer.isImmutable) {
                interpreter.typeError("TypedArray.prototype.fill called on an immutable ArrayBuffer")
            }
            val len = array.length
            // 规范顺序：先按 [[ContentType]] 执行 ToBigInt/ToNumber(value)，再归一 start/end
            val valueArgument = args.getOrElse(0) { JSUndefined }
            val value = if (array.type.isBigIntContent) {
                interpreter.conversions.toBigInt(valueArgument)
            } else {
                JSNumber(interpreter.toNumber(valueArgument))
            }
            val start = relativeIndex(interpreter, args.getOrElse(1) { JSUndefined }, len)
            val endArgument = args.getOrNull(2)
            val end = if (endArgument == null || endArgument is JSUndefined) {
                len
            } else {
                relativeIndex(interpreter, endArgument, len)
            }
            // 规范：参数转换可能分离缓冲区（coerced-start/end/value-detach 用例），写入前复检
            if (array.isDetached) {
                interpreter.typeError("TypedArray.prototype.fill called on a detached TypedArray")
            }
            var index = start
            while (index < end) {
                interpreter.setTypedArrayElement(array, index, value)
                index++
            }
            // 规范：fill 返回 this（不是 undefined）
            array
        }

        defineMethod("copyWithin", 2) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.copyWithin")
            requireAttached(interpreter, array, "TypedArray.prototype.copyWithin")
            if (array.buffer.isImmutable) {
                interpreter.typeError("TypedArray.prototype.copyWithin called on an immutable ArrayBuffer")
            }
            val len = array.length
            val target = relativeIndex(interpreter, args.getOrElse(0) { JSUndefined }, len)
            val start = relativeIndex(interpreter, args.getOrElse(1) { JSUndefined }, len)
            val endArgument = args.getOrNull(2)
            val end = if (endArgument == null || endArgument is JSUndefined) {
                len
            } else {
                relativeIndex(interpreter, endArgument, len)
            }
            // 规范：target/start/end 的转换可能分离缓冲区（coerced-values-*-detached 用例）
            if (array.isDetached) {
                interpreter.typeError("TypedArray.prototype.copyWithin called on a detached TypedArray")
            }
            var count = min(end - start, len - target)
            if (count <= 0) {
                return@defineMethod array
            }
            // 规范：同元素类型按字节拷贝；重叠区间从后往前复制。
            if (start < target) {
                array.copyRawToBackward(array, sourceStart = start, targetStart = target, count = count)
            } else {
                array.copyRawToForward(array, sourceStart = start, targetStart = target, count = count)
            }
            // 规范：copyWithin 返回 this
            array
        }

        defineMethod("map", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.map")
            requireAttached(interpreter, array, "TypedArray.prototype.map")
            val callback = args.getOrElse(0) { JSUndefined }
            requireCallback(interpreter, callback, "TypedArray.prototype.map")
            val thisArg = args.getOrElse(1) { JSUndefined }
            val len = array.length
            val result = speciesCreate(interpreter, array, len)
            var index = 0
            while (index < len) {
                interpreter.checkExecution()
                val value = array.getElement(index)
                val mapped = interpreter.callFunction(
                    callback,
                    thisArg,
                    listOf(value, JSNumber(index.toDouble()), array),
                )
                interpreter.setTypedArrayElement(result, index, mapped)
                index++
            }
            result
        }

        defineMethod("filter", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.filter")
            requireAttached(interpreter, array, "TypedArray.prototype.filter")
            val callback = args.getOrElse(0) { JSUndefined }
            requireCallback(interpreter, callback, "TypedArray.prototype.filter")
            val thisArg = args.getOrElse(1) { JSUndefined }
            val kept = ArrayList<JSValue>()
            for (index in 0 until array.length) {
                interpreter.checkExecution()
                val value = array.getElement(index)
                val selected = interpreter.toBoolean(
                    interpreter.callFunction(
                        callback,
                        thisArg,
                        listOf(value, JSNumber(index.toDouble()), array),
                    )
                )
                if (selected) kept.add(value)
            }
            val result = speciesCreate(interpreter, array, kept.size)
            kept.forEachIndexed { index, value ->
                interpreter.setTypedArrayElement(result, index, value)
            }
            result
        }

        defineMethod("forEach", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.forEach")
            requireAttached(interpreter, array, "TypedArray.prototype.forEach")
            val callback = args.getOrElse(0) { JSUndefined }
            requireCallback(interpreter, callback, "TypedArray.prototype.forEach")
            val thisArg = args.getOrElse(1) { JSUndefined }
            for (index in 0 until array.length) {
                interpreter.checkExecution()
                interpreter.callFunction(
                    callback,
                    thisArg,
                    listOf(array.getElement(index), JSNumber(index.toDouble()), array),
                )
            }
            JSUndefined
        }

        defineMethod("reduce", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.reduce")
            requireAttached(interpreter, array, "TypedArray.prototype.reduce")
            val callback = args.getOrElse(0) { JSUndefined }
            requireCallback(interpreter, callback, "TypedArray.prototype.reduce")
            val len = array.length
            var accumulator: JSValue
            var index: Int
            if (args.size >= 2) {
                accumulator = args[1]
                index = 0
            } else {
                if (len == 0) {
                    interpreter.typeError("Reduce of empty array with no initial value")
                }
                accumulator = array.getElement(0)
                index = 1
            }
            while (index < len) {
                interpreter.checkExecution()
                accumulator = interpreter.callFunction(
                    callback,
                    JSUndefined,
                    listOf(
                        accumulator,
                        array.getElement(index),
                        JSNumber(index.toDouble()),
                        array,
                    ),
                )
                index++
            }
            accumulator
        }

        defineMethod("reduceRight", 1) { interpreter, thisValue, args ->
            val array =
                requireTypedArray(interpreter, thisValue, "TypedArray.prototype.reduceRight")
            requireAttached(interpreter, array, "TypedArray.prototype.reduceRight")
            val callback = args.getOrElse(0) { JSUndefined }
            requireCallback(interpreter, callback, "TypedArray.prototype.reduceRight")
            val len = array.length
            var accumulator: JSValue
            var index: Int
            if (args.size >= 2) {
                accumulator = args[1]
                index = len - 1
            } else {
                if (len == 0) {
                    interpreter.typeError("Reduce of empty array with no initial value")
                }
                accumulator = array.getElement(len - 1)
                index = len - 2
            }
            while (index >= 0) {
                interpreter.checkExecution()
                accumulator = interpreter.callFunction(
                    callback,
                    JSUndefined,
                    listOf(
                        accumulator,
                        array.getElement(index),
                        JSNumber(index.toDouble()),
                        array,
                    ),
                )
                index--
            }
            accumulator
        }

        defineMethod("join", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.join")
            requireAttached(interpreter, array, "TypedArray.prototype.join")
            val separatorArgument = args.getOrElse(0) { JSUndefined }
            val separator = if (separatorArgument is JSUndefined) {
                ","
            } else {
                interpreter.toStringValue(separatorArgument)
            }
            joinElements(interpreter, array, separator)
        }

        defineMethod("indexOf", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.indexOf")
            requireAttached(interpreter, array, "TypedArray.prototype.indexOf")
            val searchElement = args.getOrElse(0) { JSUndefined }
            val len = array.length
            if (len == 0) return@defineMethod JSNumber(-1.0)
            val fromIndex = toIntegerOrInfinity(interpreter, args.getOrElse(1) { JSUndefined })
            // 规范：indexOf 逐项先用 HasProperty 判断；分离后所有整数索引都不存在，
            // 因此 fromIndex 转换期间分离会得到 -1（元素本身不会被读取）
            if (array.isDetached) return@defineMethod JSNumber(-1.0)
            if (fromIndex == Double.POSITIVE_INFINITY) return@defineMethod JSNumber(-1.0)
            var index = if (fromIndex >= 0.0) {
                min(fromIndex, len.toDouble()).toInt()
            } else {
                kotlin.math.max(len + fromIndex, 0.0).toInt()
            }
            while (index < len) {
                if (interpreter.strictEquals(array.getElement(index), searchElement)) {
                    return@defineMethod JSNumber(index.toDouble())
                }
                index++
            }
            JSNumber(-1.0)
        }

        defineMethod("lastIndexOf", 1) { interpreter, thisValue, args ->
            val array =
                requireTypedArray(interpreter, thisValue, "TypedArray.prototype.lastIndexOf")
            requireAttached(interpreter, array, "TypedArray.prototype.lastIndexOf")
            val searchElement = args.getOrElse(0) { JSUndefined }
            val len = array.length
            if (len == 0) return@defineMethod JSNumber(-1.0)
            // 规范：只有真正"缺省" fromIndex 才是 +∞；显式 undefined 会 ToIntegerOrInfinity 成 0
            val fromIndex = if (args.size < 2) {
                Double.POSITIVE_INFINITY
            } else {
                toIntegerOrInfinity(interpreter, args[1])
            }
            // 与 indexOf 同理：分离后 HasProperty 恒为 false
            if (array.isDetached) return@defineMethod JSNumber(-1.0)
            if (fromIndex == Double.NEGATIVE_INFINITY) return@defineMethod JSNumber(-1.0)
            var index = if (fromIndex >= 0.0) {
                min(fromIndex, (len - 1).toDouble()).toInt()
            } else {
                (len + fromIndex).toInt()
            }
            while (index >= 0) {
                if (interpreter.strictEquals(array.getElement(index), searchElement)) {
                    return@defineMethod JSNumber(index.toDouble())
                }
                index--
            }
            JSNumber(-1.0)
        }

        defineMethod("includes", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.includes")
            requireAttached(interpreter, array, "TypedArray.prototype.includes")
            val searchElement = args.getOrElse(0) { JSUndefined }
            val len = array.length
            if (len == 0) return@defineMethod JSBoolean.getJsBoolean(false)
            val fromIndex = toIntegerOrInfinity(interpreter, args.getOrElse(1) { JSUndefined })
            if (fromIndex == Double.POSITIVE_INFINITY) {
                return@defineMethod JSBoolean.getJsBoolean(false)
            }
            var index = if (fromIndex >= 0.0) {
                min(fromIndex, len.toDouble()).toInt()
            } else {
                kotlin.math.max(len + fromIndex, 0.0).toInt()
            }
            while (index < len) {
                if (interpreter.sameValueZero(array.getElement(index), searchElement)) {
                    return@defineMethod JSBoolean.getJsBoolean(true)
                }
                index++
            }
            JSBoolean.getJsBoolean(false)
        }

        defineMethod("find", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.find")
            requireAttached(interpreter, array, "TypedArray.prototype.find")
            val predicate = args.getOrElse(0) { JSUndefined }
            requireCallback(interpreter, predicate, "TypedArray.prototype.find")
            val thisArg = args.getOrElse(1) { JSUndefined }
            for (index in 0 until array.length) {
                interpreter.checkExecution()
                val value = array.getElement(index)
                if (interpreter.toBoolean(
                        interpreter.callFunction(
                            predicate,
                            thisArg,
                            listOf(value, JSNumber(index.toDouble()), array),
                        )
                    )
                ) {
                    return@defineMethod value
                }
            }
            JSUndefined
        }

        defineMethod("findIndex", 1) { interpreter, thisValue, args ->
            val array =
                requireTypedArray(interpreter, thisValue, "TypedArray.prototype.findIndex")
            requireAttached(interpreter, array, "TypedArray.prototype.findIndex")
            val predicate = args.getOrElse(0) { JSUndefined }
            requireCallback(interpreter, predicate, "TypedArray.prototype.findIndex")
            val thisArg = args.getOrElse(1) { JSUndefined }
            for (index in 0 until array.length) {
                interpreter.checkExecution()
                if (interpreter.toBoolean(
                        interpreter.callFunction(
                            predicate,
                            thisArg,
                            listOf(
                                array.getElement(index),
                                JSNumber(index.toDouble()),
                                array,
                            ),
                        )
                    )
                ) {
                    return@defineMethod JSNumber(index.toDouble())
                }
            }
            JSNumber(-1.0)
        }

        defineMethod("findLast", 1) { interpreter, thisValue, args ->
            val array =
                requireTypedArray(interpreter, thisValue, "TypedArray.prototype.findLast")
            requireAttached(interpreter, array, "TypedArray.prototype.findLast")
            val predicate = args.getOrElse(0) { JSUndefined }
            requireCallback(interpreter, predicate, "TypedArray.prototype.findLast")
            val thisArg = args.getOrElse(1) { JSUndefined }
            var index = array.length - 1
            while (index >= 0) {
                interpreter.checkExecution()
                val value = array.getElement(index)
                if (interpreter.toBoolean(
                        interpreter.callFunction(
                            predicate,
                            thisArg,
                            listOf(value, JSNumber(index.toDouble()), array),
                        )
                    )
                ) {
                    return@defineMethod value
                }
                index--
            }
            JSUndefined
        }

        defineMethod("findLastIndex", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(
                interpreter,
                thisValue,
                "TypedArray.prototype.findLastIndex",
            )
            requireAttached(interpreter, array, "TypedArray.prototype.findLastIndex")
            val predicate = args.getOrElse(0) { JSUndefined }
            requireCallback(interpreter, predicate, "TypedArray.prototype.findLastIndex")
            val thisArg = args.getOrElse(1) { JSUndefined }
            var index = array.length - 1
            while (index >= 0) {
                interpreter.checkExecution()
                if (interpreter.toBoolean(
                        interpreter.callFunction(
                            predicate,
                            thisArg,
                            listOf(
                                array.getElement(index),
                                JSNumber(index.toDouble()),
                                array,
                            ),
                        )
                    )
                ) {
                    return@defineMethod JSNumber(index.toDouble())
                }
                index--
            }
            JSNumber(-1.0)
        }

        defineMethod("some", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.some")
            requireAttached(interpreter, array, "TypedArray.prototype.some")
            val predicate = args.getOrElse(0) { JSUndefined }
            requireCallback(interpreter, predicate, "TypedArray.prototype.some")
            val thisArg = args.getOrElse(1) { JSUndefined }
            for (index in 0 until array.length) {
                interpreter.checkExecution()
                if (interpreter.toBoolean(
                        interpreter.callFunction(
                            predicate,
                            thisArg,
                            listOf(
                                array.getElement(index),
                                JSNumber(index.toDouble()),
                                array,
                            ),
                        )
                    )
                ) {
                    return@defineMethod JSBoolean.getJsBoolean(true)
                }
            }
            JSBoolean.getJsBoolean(false)
        }

        defineMethod("every", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.every")
            requireAttached(interpreter, array, "TypedArray.prototype.every")
            val predicate = args.getOrElse(0) { JSUndefined }
            requireCallback(interpreter, predicate, "TypedArray.prototype.every")
            val thisArg = args.getOrElse(1) { JSUndefined }
            for (index in 0 until array.length) {
                interpreter.checkExecution()
                if (!interpreter.toBoolean(
                        interpreter.callFunction(
                            predicate,
                            thisArg,
                            listOf(
                                array.getElement(index),
                                JSNumber(index.toDouble()),
                                array,
                            ),
                        )
                    )
                ) {
                    return@defineMethod JSBoolean.getJsBoolean(false)
                }
            }
            JSBoolean.getJsBoolean(true)
        }

        defineMethod("sort", 1) { interpreter, thisValue, args ->
            // 规范顺序：先校验 comparefn 可调用，再做品牌检查
            val comparefnArgument = args.getOrNull(0)
            val comparefn = if (comparefnArgument == null || comparefnArgument is JSUndefined) {
                null
            } else {
                if (!interpreter.isCallable(comparefnArgument)) {
                    interpreter.typeError(
                        "The comparison function must be either a function or undefined"
                    )
                }
                comparefnArgument
            }
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.sort")
            requireAttached(interpreter, array, "TypedArray.prototype.sort")
            if (array.buffer.isImmutable) {
                interpreter.typeError("TypedArray.prototype.sort called on an immutable ArrayBuffer")
            }
            val len = array.length
            if (len <= 1) return@defineMethod array
            val values = ArrayList<JSValue>(len)
            for (index in 0 until len) values.add(array.getElement(index))
            mergeSort(interpreter, values) { left, right ->
                compareForSort(interpreter, left, right, comparefn)
            }
            for (index in 0 until len) {
                interpreter.setTypedArrayElement(array, index, values[index])
            }
            array
        }

        defineMethod("reverse", 0) { interpreter, thisValue, _ ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.reverse")
            requireAttached(interpreter, array, "TypedArray.prototype.reverse")
            if (array.buffer.isImmutable) {
                interpreter.typeError("TypedArray.prototype.reverse called on an immutable ArrayBuffer")
            }
            val len = array.length
            val middle = len / 2
            var lower = 0
            while (lower < middle) {
                val upper = len - lower - 1
                val lowerValue = array.getElement(lower)
                val upperValue = array.getElement(upper)
                interpreter.setTypedArrayElement(array, lower, upperValue)
                interpreter.setTypedArrayElement(array, upper, lowerValue)
                lower++
            }
            array
        }

        // 以下三个是 ES2023 的"复制型"方法：结果使用同类型默认构造器（不是 species）

        defineMethod("toReversed", 0) { interpreter, thisValue, _ ->
            val array =
                requireTypedArray(interpreter, thisValue, "TypedArray.prototype.toReversed")
            requireAttached(interpreter, array, "TypedArray.prototype.toReversed")
            val len = array.length
            val result = createSameType(interpreter, array, len)
            var index = 0
            while (index < len) {
                interpreter.setTypedArrayElement(
                    result,
                    index,
                    array.getElement(len - index - 1),
                )
                index++
            }
            result
        }

        defineMethod("toSorted", 1) { interpreter, thisValue, args ->
            // 规范顺序：先校验 comparefn，再品牌检查
            val comparefnArgument = args.getOrNull(0)
            val comparefn = if (comparefnArgument == null || comparefnArgument is JSUndefined) {
                null
            } else {
                if (!interpreter.isCallable(comparefnArgument)) {
                    interpreter.typeError(
                        "The comparison function must be either a function or undefined"
                    )
                }
                comparefnArgument
            }
            val array =
                requireTypedArray(interpreter, thisValue, "TypedArray.prototype.toSorted")
            requireAttached(interpreter, array, "TypedArray.prototype.toSorted")
            val len = array.length
            val result = createSameType(interpreter, array, len)
            val values = ArrayList<JSValue>(len)
            for (index in 0 until len) values.add(array.getElement(index))
            mergeSort(interpreter, values) { left, right ->
                compareForSort(interpreter, left, right, comparefn)
            }
            for (index in 0 until len) {
                interpreter.setTypedArrayElement(result, index, values[index])
            }
            result
        }

        defineMethod("with", 2) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.with")
            requireAttached(interpreter, array, "TypedArray.prototype.with")
            val len = array.length
            val relativeIndex =
                toIntegerOrInfinity(interpreter, args.getOrElse(0) { JSUndefined })
            val actualIndex = if (relativeIndex >= 0.0) relativeIndex else len + relativeIndex
            // 规范顺序：先按 [[ContentType]] 执行 ToBigInt/ToNumber(value)（可能抛错），
            // 再检查下标范围并复制
            val valueArgument = args.getOrElse(1) { JSUndefined }
            val numericValue = if (array.type.isBigIntContent) {
                interpreter.conversions.toBigInt(valueArgument)
            } else {
                JSNumber(interpreter.toNumber(valueArgument))
            }
            if (actualIndex < 0.0 || actualIndex >= len.toDouble()) {
                interpreter.rangeError("Invalid typed array index")
            }
            val result = createSameType(interpreter, array, len)
            for (index in 0 until len) {
                val value = if (index == actualIndex.toInt()) {
                    numericValue
                } else {
                    array.getElement(index)
                }
                interpreter.setTypedArrayElement(result, index, value)
            }
            result
        }

        defineMethod("at", 1) { interpreter, thisValue, args ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.at")
            requireAttached(interpreter, array, "TypedArray.prototype.at")
            val len = array.length
            val relativeIndex = toIntegerOrInfinity(interpreter, args.getOrElse(0) { JSUndefined })
            val index = if (relativeIndex >= 0.0) relativeIndex else len + relativeIndex
            if (index < 0.0 || index >= len.toDouble()) {
                JSUndefined
            } else {
                array.getElement(index.toInt())
            }
        }

        defineMethod("entries", 0) { interpreter, thisValue, _ ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.entries")
            requireAttached(interpreter, array, "TypedArray.prototype.entries")
            IteratorBuiltins.createArrayIterator(
                interpreter.intrinsics,
                array,
                IteratorBuiltins.ArrayIteratorKind.ENTRIES,
            )
        }

        defineMethod("keys", 0) { interpreter, thisValue, _ ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.keys")
            requireAttached(interpreter, array, "TypedArray.prototype.keys")
            IteratorBuiltins.createArrayIterator(
                interpreter.intrinsics,
                array,
                IteratorBuiltins.ArrayIteratorKind.KEYS,
            )
        }

        // values 与 @@iterator 必须是同一个函数对象
        val valuesFunction = BuiltinHelpers.method(intrinsics, "values", 0) {
                interpreter, thisValue, _ ->
            val array = requireTypedArray(interpreter, thisValue, "TypedArray.prototype.values")
            requireAttached(interpreter, array, "TypedArray.prototype.values")
            IteratorBuiltins.createArrayIterator(
                interpreter.intrinsics,
                array,
                IteratorBuiltins.ArrayIteratorKind.VALUES,
            )
        }
        prototype.defineDataProperty(PropertyKey.Str("values"), valuesFunction)
        prototype.defineDataProperty(
            PropertyKey.Sym(intrinsics.symbolIterator),
            valuesFunction,
        )

        defineMethod("toLocaleString", 0) { interpreter, thisValue, _ ->
            val array =
                requireTypedArray(interpreter, thisValue, "TypedArray.prototype.toLocaleString")
            requireAttached(interpreter, array, "TypedArray.prototype.toLocaleString")
            if (array.length == 0) {
                JSString("")
            } else {
                val builder = StringBuilder()
                for (index in 0 until array.length) {
                    if (index > 0) builder.append(',')
                    val value = array.getElement(index)
                    if (value !is JSUndefined && value !is JSNull) {
                        val method =
                            interpreter.getV(value, PropertyKey.Str("toLocaleString"))
                        builder.append(
                            interpreter.toStringValue(
                                interpreter.callFunction(method, value, emptyList())
                            )
                        )
                    }
                }
                JSString(builder.toString())
            }
        }

        // 规范：%TypedArray%.prototype.toString 与 Array.prototype.toString 是同一个函数对象；
        // Array.prototype.toString 会 Invoke(this, "join")，正好复用本原型上的 join
        val arrayToString = intrinsics.arrayPrototype
            .getOwnProperty(PropertyKey.Str("toString"))
            ?.value
        if (arrayToString != null) {
            prototype.defineDataProperty(
                key = PropertyKey.Str("toString"),
                value = arrayToString,
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }
    }

    // ================================================================
    // 构造算法
    // ================================================================

    /**
     * TypedArray 构造器的 [[Construct]]。
     *
     * 实例原型由 NewTarget 决定，保证 `class X extends Uint8Array` 的子类实例正确。
     */
    private suspend fun constructTypedArray(
        interpreter: Interpreter,
        type: JSTypedArrayType,
        args: List<JSValue>,
        newTarget: JSValue,
    ): JSValue {
        val defaultPrototype = interpreter.intrinsics.typedArrayPrototypes.getValue(type)
        val firstArgument = args.getOrElse(0) { JSUndefined }
        return when (firstArgument) {
            is JSTypedArray -> {
                // 读取 length 是内部槽操作，不会执行 JS，可以先解析原型
                val prototype =
                    interpreter.prototypeFromNewTarget(newTarget, defaultPrototype)
                fromTypedArray(interpreter, type, firstArgument, prototype)
            }

            is JSArrayBuffer -> fromArrayBuffer(
                interpreter = interpreter,
                type = type,
                buffer = firstArgument,
                args = args,
                newTarget = newTarget,
                defaultPrototype = defaultPrototype,
            )

            is JSObject -> fromObject(
                interpreter = interpreter,
                type = type,
                source = firstArgument,
                newTarget = newTarget,
                defaultPrototype = defaultPrototype,
            )

            else -> {
                // 规范顺序：先 ToIndex（Symbol 等会在这一步抛 TypeError），再解析实例原型
                val elementLength = interpreter.toIndex(firstArgument)
                val prototype =
                    interpreter.prototypeFromNewTarget(newTarget, defaultPrototype)
                allocate(interpreter, type, elementLength, prototype)
            }
        }
    }

    /** new T(typedArray)：分配等长缓冲区后按元素拷贝。 */
    private suspend fun fromTypedArray(
        interpreter: Interpreter,
        type: JSTypedArrayType,
        source: JSTypedArray,
        prototype: JSObject,
    ): JSTypedArray {
        // 规范：InitializeTypedArrayFromTypedArray 先检查 IsTypedArrayOutOfBounds，
        // 分离缓冲区属于 out-of-bounds，直接抛 TypeError（不分配新缓冲区）
        if (source.isDetached) {
            interpreter.typeError("Cannot construct a TypedArray from a detached TypedArray")
        }
        val result = allocate(interpreter, type, source.length.toLong(), prototype)
        if (source.type == type) {
            // 同类型按字节复制，与逐元素读取等价且更快（方向：source → result）
            result.copyElementsFrom(source, source.length)
        } else {
            for (index in 0 until source.length) {
                interpreter.checkExecution()
                interpreter.setTypedArrayElement(result, index, source.getElement(index))
            }
        }
        return result
    }

    /** new T(arrayBuffer, byteOffset?, length?)：在已有缓冲区上建立视图。 */
    private suspend fun fromArrayBuffer(
        interpreter: Interpreter,
        type: JSTypedArrayType,
        buffer: JSArrayBuffer,
        args: List<JSValue>,
        newTarget: JSValue,
        defaultPrototype: JSObject,
    ): JSTypedArray {
        val elementSize = type.bytesPerElement
        val byteOffset = interpreter.toIndex(args.getOrElse(1) { JSUndefined })
        // 规范：ToIndex(byteOffset) 之后检查 IsDetachedBuffer（可观察的 valueOf 已执行）
        if (buffer.isDetached) {
            interpreter.typeError("First argument to TypedArray constructor must not be detached")
        }
        val bufferByteLength = buffer.byteLength.toLong()
        // 规范：起始偏移必须是元素宽度的整数倍
        if (byteOffset % elementSize != 0L) {
            interpreter.rangeError(
                "start offset of ${type.typeName} should be a multiple of $elementSize"
            )
        }
        if (byteOffset > bufferByteLength) {
            interpreter.rangeError(
                "Start offset $byteOffset is outside the bounds of the ArrayBuffer"
            )
        }
        val remainingByteLength = bufferByteLength - byteOffset
        val lengthArgument = args.getOrNull(2)
        val lengthTracking = lengthArgument == null || lengthArgument is JSUndefined
        val elementLength = if (lengthTracking) {
            // 缺省：视图延伸到缓冲区末尾，剩余字节必须能被元素宽度整除
            if (remainingByteLength % elementSize != 0L) {
                interpreter.rangeError(
                    "byte length of ${type.typeName} should be a multiple of $elementSize"
                )
            }
            remainingByteLength / elementSize
        } else {
            val requested = interpreter.toIndex(lengthArgument)
            // 规范：ToIndex(length) 之后再次检查 IsDetachedBuffer
            if (buffer.isDetached) {
                interpreter.typeError("First argument to TypedArray constructor must not be detached")
            }
            if (requested * elementSize > remainingByteLength) {
                interpreter.rangeError("Invalid ${type.typeName} length")
            }
            requested
        }
        // 参数处理完成后再解析 NewTarget.prototype（可能触发 getter）
        val prototype = interpreter.prototypeFromNewTarget(newTarget, defaultPrototype)
        return create(
            interpreter = interpreter,
            type = type,
            buffer = buffer,
            byteOffset = byteOffset,
            elementLength = elementLength,
            prototype = prototype,
            lengthTracking = lengthTracking,
        )
    }

    /** new T(iterable | arrayLike)：可迭代对象优先，取不到 @@iterator 时按类数组处理。 */
    private suspend fun fromObject(
        interpreter: Interpreter,
        type: JSTypedArrayType,
        source: JSObject,
        newTarget: JSValue,
        defaultPrototype: JSObject,
    ): JSTypedArray {
        val iteratorMethod = interpreter.getV(
            source,
            PropertyKey.Sym(interpreter.intrinsics.symbolIterator),
        )
        if (iteratorMethod !is JSUndefined && iteratorMethod !is JSNull) {
            if (!interpreter.isCallable(iteratorMethod)) {
                interpreter.typeError("@@iterator is not a function")
            }
            // 先收集全部元素再分配：与规范的 InitializeTypedArrayFromList 一致
            val values = interpreter.iterateValues(source)
            val prototype = interpreter.prototypeFromNewTarget(newTarget, defaultPrototype)
            return fromList(interpreter, type, values, prototype)
        }
        val sourceLength = toLength(
            interpreter,
            interpreter.getV(source, PropertyKey.LENGTH),
        )
        val prototype = interpreter.prototypeFromNewTarget(newTarget, defaultPrototype)
        val result = allocate(interpreter, type, sourceLength, prototype)
        var index = 0L
        while (index < sourceLength) {
            interpreter.checkExecution()
            val value = interpreter.getV(source, PropertyKey.Str(index.toString()))
            interpreter.setTypedArrayElement(result, index.toInt(), value)
            index++
        }
        return result
    }

    /** InitializeTypedArrayFromList：元素先收集，长度即元素个数。 */
    private suspend fun fromList(
        interpreter: Interpreter,
        type: JSTypedArrayType,
        values: List<JSValue>,
        prototype: JSObject,
    ): JSTypedArray {
        val result = allocate(interpreter, type, values.size.toLong(), prototype)
        for (index in values.indices) {
            interpreter.checkExecution()
            interpreter.setTypedArrayElement(result, index, values[index])
        }
        return result
    }

    /** 分配新的 ArrayBuffer 并创建指定的视图。 */
    private fun allocate(
        interpreter: Interpreter,
        type: JSTypedArrayType,
        elementLength: Long,
        prototype: JSObject,
    ): JSTypedArray {
        val byteLength = elementLength * type.bytesPerElement
        if (byteLength > Int.MAX_VALUE.toLong()) {
            interpreter.rangeError("Invalid typed array length")
        }
        val buffer = JSArrayBuffer(
            prototype = interpreter.intrinsics.arrayBufferPrototype,
            byteLength = byteLength.toInt(),
        )
        return JSTypedArray(
            prototype = prototype,
            type = type,
            buffer = buffer,
            byteOffset = 0,
            length = elementLength.toInt(),
        )
    }

    /** 在已有缓冲区上创建视图；偏移与长度必须落在 Int 可见范围。 */
    private fun create(
        interpreter: Interpreter,
        type: JSTypedArrayType,
        buffer: JSArrayBuffer,
        byteOffset: Long,
        elementLength: Long,
        prototype: JSObject,
        lengthTracking: Boolean = false,
    ): JSTypedArray {
        if (byteOffset > Int.MAX_VALUE.toLong() || elementLength > Int.MAX_VALUE.toLong()) {
            interpreter.rangeError("Invalid typed array length")
        }
        return JSTypedArray(
            prototype = prototype,
            type = type,
            buffer = buffer,
            byteOffset = byteOffset.toInt(),
            length = elementLength.toInt(),
            lengthTracking = lengthTracking,
        )
    }

    // ================================================================
    // set
    // ================================================================

    /** TypedArray.prototype.set 的主体：typed array 快路径 + 类数组慢路径。 */
    private suspend fun setFromSource(
        interpreter: Interpreter,
        target: JSTypedArray,
        source: JSValue,
        offset: JSValue,
    ) {
        if (target.buffer.isImmutable) {
            interpreter.typeError("TypedArray.prototype.set called on an immutable ArrayBuffer")
        }
        val targetOffset = toIntegerOrInfinity(interpreter, offset)
        if (targetOffset < 0.0) {
            interpreter.rangeError("offset is out of bounds")
        }
        // 规范：ToIntegerOrInfinity(offset) 可能分离目标缓冲区，读取源之前必须复检
        requireAttached(interpreter, target, "TypedArray.prototype.set")
        val targetLength = target.length
        if (source is JSTypedArray) {
            // 规范：源 TypedArray 的缓冲区分离时抛 TypeError（先于长度与范围检查）
            if (source.isDetached) {
                interpreter.typeError("Source TypedArray has a detached buffer")
            }
            val sourceLength = source.length
            if (targetOffset + sourceLength > targetLength.toDouble()) {
                interpreter.rangeError("Source is too large")
            }
            val start = targetOffset.toInt()
            if (source.type == target.type) {
                // 规范：相同元素类型按原始字节拷贝以保留 NaN payload/负零。
                source.copyRawTo(target, sourceStart = 0, targetStart = start, count = sourceLength)
                return
            }
            if (source.buffer === target.buffer) {
                // 规范：同一缓冲区上的重叠拷贝等价于先复制到临时列表
                val values = ArrayList<JSValue>(sourceLength)
                for (index in 0 until sourceLength) values.add(source.getElement(index))
                for (index in 0 until sourceLength) {
                    interpreter.setTypedArrayElement(target, start + index, values[index])
                }
            } else {
                for (index in 0 until sourceLength) {
                    interpreter.checkExecution()
                    interpreter.setTypedArrayElement(
                        target,
                        start + index,
                        source.getElement(index),
                    )
                }
            }
            return
        }
        // 非 TypedArray：按数组式对象处理（LengthOfArrayLike + Get 每个下标）
        val sourceObject = interpreter.toObject(source)
        val sourceLength = toLength(
            interpreter,
            interpreter.getV(sourceObject, PropertyKey.LENGTH),
        )
        if (targetOffset + sourceLength > targetLength.toDouble()) {
            interpreter.rangeError("Source is too large")
        }
        val start = targetOffset.toInt()
        var index = 0L
        while (index < sourceLength) {
            interpreter.checkExecution()
            val value = interpreter.getV(sourceObject, PropertyKey.Str(index.toString()))
            interpreter.setTypedArrayElement(target, start + index.toInt(), value)
            index++
        }
    }

    // ================================================================
    // species / 通用辅助
    // ================================================================

    /** TypedArraySpeciesCreate：按 species 创建结果并校验类型与长度。 */
    private suspend fun speciesCreate(
        interpreter: Interpreter,
        exemplar: JSTypedArray,
        length: Int,
    ): JSTypedArray {
        val constructor = interpreter.speciesConstructor(
            exemplar,
            defaultConstructor(interpreter, exemplar.type),
        )
        val created = interpreter.construct(constructor, listOf(JSNumber(length.toDouble())))
        val result = created as? JSTypedArray
            ?: interpreter.typeError("Species constructor did not return a TypedArray")
        if (result.buffer.isImmutable) {
            interpreter.typeError("Species constructor returned a TypedArray backed by an immutable ArrayBuffer")
        }
        // 规范不再要求 species 返回相同元素类型（跨类型结果按目标类型转换写入）
        if (result.length < length) {
            interpreter.typeError("Species constructor returned a too-small TypedArray")
        }
        return result
    }

    /** subarray：在同一缓冲区上按 species 建立共享视图。 */
    private suspend fun subarray(
        interpreter: Interpreter,
        array: JSTypedArray,
        args: List<JSValue>,
    ): JSValue {
        // TypedArrayLength：缓冲区已分离时按规范取 0；此时若 species 使用默认构造器，
        // 会因"绑定分离缓冲区"抛 TypeError，而自定义 species 仍可返回自己的结果。
        val sourceLength = if (array.isDetached) 0 else array.length
        val begin = relativeIndex(interpreter, args.getOrElse(0) { JSUndefined }, sourceLength)
        val endArgument = args.getOrNull(1)
        val end = if (endArgument == null || endArgument is JSUndefined) {
            sourceLength
        } else {
            relativeIndex(interpreter, endArgument, sourceLength)
        }
        val newLength = (end - begin).coerceAtLeast(0)
        val constructor = interpreter.speciesConstructor(
            array,
            defaultConstructor(interpreter, array.type),
        )
        val byteOffset = array.byteOffset + begin * array.bytesPerElement
        val constructorArguments = if (
            array.isLengthTracking && array.buffer.isResizable &&
            (endArgument == null || endArgument is JSUndefined)
        ) {
            // length-tracking 视图的 subarray 不传显式长度，结果继续跟踪缓冲区。
            listOf(array.buffer, JSNumber(byteOffset.toDouble()))
        } else {
            listOf(
                array.buffer,
                JSNumber(byteOffset.toDouble()),
                JSNumber(newLength.toDouble()),
            )
        }
        val created = interpreter.construct(constructor, constructorArguments)
        return created as? JSTypedArray
            ?: interpreter.typeError("Species constructor did not return a TypedArray")
    }

    /** 取某元素类型对应的内建构造器（SpeciesConstructor 的默认值）。 */
    private fun defaultConstructor(interpreter: Interpreter, type: JSTypedArrayType): JSValue =
        interpreter.intrinsics.typedArrayConstructors.getValue(type)

    /**
     * TypedArrayCreateSameType：用同类型默认构造器创建结果。
     *
     * ES2023 的 toReversed/toSorted/with 使用它；与 species 不同，结果元素类型恒为原类型。
     */
    private suspend fun createSameType(
        interpreter: Interpreter,
        exemplar: JSTypedArray,
        length: Int,
    ): JSTypedArray = typedArrayCreateFromConstructor(
        interpreter = interpreter,
        constructor = defaultConstructor(interpreter, exemplar.type),
        length = length,
    )

    /** ToIntegerOrInfinity：NaN/±0 归 0，保留 ±Infinity。 */
    private suspend fun toIntegerOrInfinity(interpreter: Interpreter, value: JSValue): Double {
        val number = interpreter.toNumber(value)
        return when {
            number.isNaN() -> 0.0
            number == 0.0 || !number.isFinite() -> number
            else -> truncate(number)
        }
    }

    /** 把 start/end 归一为 [0, length] 区间内的 Int 下标。 */
    private suspend fun relativeIndex(
        interpreter: Interpreter,
        value: JSValue,
        length: Int,
    ): Int {
        val integer = toIntegerOrInfinity(interpreter, value)
        return when {
            integer == Double.NEGATIVE_INFINITY -> 0
            integer < 0.0 -> kotlin.math.max(length + integer, 0.0).toInt()
            else -> min(integer, length.toDouble()).toInt()
        }
    }

    /** ToLength：NaN/负数归 0，上限 2^53-1。 */
    private suspend fun toLength(interpreter: Interpreter, value: JSValue): Long {
        val number = interpreter.toNumber(value)
        return when {
            number.isNaN() || number <= 0.0 -> 0L
            number >= 9007199254740991.0 -> 9007199254740991L
            else -> kotlin.math.floor(number).toLong()
        }
    }

    /** 品牌检查：%TypedArray%.prototype 的方法只接受真正的 TypedArray。 */
    private fun requireTypedArray(
        interpreter: Interpreter,
        thisValue: JSValue,
        name: String,
    ): JSTypedArray = thisValue as? JSTypedArray
        ?: interpreter.typeError("$name requires that 'this' be a TypedArray")

    /**
     * ValidateTypedArray 的分离检查部分：底层缓冲区已分离时抛 TypeError。
     *
     * 大多数 %TypedArray%.prototype 方法在品牌检查后都会执行 ValidateTypedArray，
     * 分离即视为 out-of-bounds；少数方法（buffer/byteOffset/byteLength/length/
     * @@toStringTag 访问器与元素读写）不执行该检查，因此不能统一放在品牌检查里。
     */
    private fun requireAttached(
        interpreter: Interpreter,
        array: JSTypedArray,
        name: String,
    ) {
        if (array.isDetached) {
            interpreter.typeError("$name called on a TypedArray with a detached buffer")
        }
    }

    /** 回调参数校验。 */
    private fun requireCallback(interpreter: Interpreter, callback: JSValue, name: String) {
        if (!interpreter.isCallable(callback)) {
            interpreter.typeError("$name callback is not a function")
        }
    }

    /** join/toLocaleString 共用：TypedArray 的元素不会出现 undefined，但仍按规范处理。 */
    private suspend fun joinElements(
        interpreter: Interpreter,
        array: JSTypedArray,
        separator: String,
    ): JSValue {
        if (array.length == 0) return JSString("")
        val builder = StringBuilder()
        for (index in 0 until array.length) {
            if (index > 0) builder.append(separator)
            val value = array.getElement(index)
            if (value !is JSUndefined && value !is JSNull) {
                builder.append(interpreter.toStringValue(value))
            }
        }
        return JSString(builder.toString())
    }

    // ================================================================
    // sort
    // ================================================================

    /** SortCompare：有 comparefn 时调用它（NaN 视为 0），否则按数值升序、NaN 最后。 */
    private suspend fun compareForSort(
        interpreter: Interpreter,
        left: JSValue,
        right: JSValue,
        comparefn: JSValue?,
    ): Int {
        if (comparefn != null) {
            val result = interpreter.toNumber(
                interpreter.callFunction(comparefn, JSUndefined, listOf(left, right))
            )
            if (result.isNaN()) return 0
            return if (result < 0.0) -1 else if (result > 0.0) 1 else 0
        }
        // BigInt 视图的元素没有 NaN/-0，直接按数学大小比较
        if (left is JSBigInt && right is JSBigInt) return left.compareTo(right)
        val leftNumber = (left as JSNumber).value
        val rightNumber = (right as JSNumber).value
        // 规范：-0 排在 +0 之前（与数值比较相等的情况区分开）
        val leftIsNegativeZero = leftNumber == 0.0 && 1.0 / leftNumber < 0.0
        val rightIsNegativeZero = rightNumber == 0.0 && 1.0 / rightNumber < 0.0
        return when {
            leftNumber.isNaN() && rightNumber.isNaN() -> 0
            leftNumber.isNaN() -> 1
            rightNumber.isNaN() -> -1
            leftIsNegativeZero && rightNumber == 0.0 && !rightIsNegativeZero -> -1
            rightIsNegativeZero && leftNumber == 0.0 && !leftIsNegativeZero -> 1
            leftNumber < rightNumber -> -1
            leftNumber > rightNumber -> 1
            else -> 0
        }
    }

    /**
     * 稳定归并排序（规范要求 sort 稳定）。
     *
     * 比较器是 suspend（可能执行任意 JS），因此不能使用 Kotlin 的 sortedWith。
     */
    private suspend fun mergeSort(
        interpreter: Interpreter,
        values: MutableList<JSValue>,
        compare: suspend (JSValue, JSValue) -> Int,
    ) {
        val size = values.size
        if (size < 2) return
        val buffer = ArrayList<JSValue>(size)
        var width = 1
        while (width < size) {
            var start = 0
            while (start < size) {
                interpreter.checkExecution()
                val middle = min(start + width, size)
                val end = min(start + 2 * width, size)
                var left = start
                var right = middle
                buffer.clear()
                while (left < middle && right < end) {
                    if (compare(values[left], values[right]) <= 0) {
                        buffer.add(values[left])
                        left++
                    } else {
                        buffer.add(values[right])
                        right++
                    }
                }
                while (left < middle) buffer.add(values[left++])
                while (right < end) buffer.add(values[right++])
                for (offset in buffer.indices) {
                    values[start + offset] = buffer[offset]
                }
                start = end
            }
            // 防止 width 溢出，同时在覆盖整个数组后结束
            width = if (width > size / 2) size else width * 2
        }
    }

    /** 规范中 BYTES_PER_ELEMENT 的属性键。 */
    private val BYTES_PER_ELEMENT = PropertyKey.Str("BYTES_PER_ELEMENT")
}
