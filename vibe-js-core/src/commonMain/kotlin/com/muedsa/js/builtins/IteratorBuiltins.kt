package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.Intrinsics
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyDescriptor
import com.muedsa.js.runtime.value.PropertyKey

/**
 * 迭代器工具：基础迭代器协议、Iterator 构造器与 ES2025 Iterator helpers。
 */
internal object IteratorBuiltins {

    /** 迭代器直接记录：[[Iterator]] 与缓存的 next 方法（可调用性延迟到调用时校验）。 */
    private class DirectIterator(val iterator: JSObject, val nextMethod: JSValue)

    /**
     * Iterator Helper 的内部状态：共享原型 next/return 通过它分发，
     * 同时承载生成器状态（done/running）。
     */
    private interface HelperState {
        var done: Boolean
        var running: Boolean

        /** 是否已产出过值（suspended-yield）；false 表示 suspended-start。 */
        var started: Boolean

        /** 推进并返回值；null 表示正常完成。 */
        suspend fun advance(interpreter: Interpreter): JSValue?

        /** return() 时的关闭逻辑（正常完成语义）。 */
        suspend fun close(interpreter: Interpreter)
    }

    /** map/filter/take/drop/flatMap 的 helper 状态。 */
    private class ProducerHelperState(
        private val record: DirectIterator,
        private val advanceFn: suspend (Interpreter) -> JSValue?,
        private val onReturn: (suspend (Interpreter) -> Unit)?,
    ) : HelperState {
        override var done: Boolean = false
        override var running: Boolean = false
        override var started: Boolean = false

        override suspend fun advance(interpreter: Interpreter): JSValue? = advanceFn(interpreter)

        override suspend fun close(interpreter: Interpreter) {
            onReturn?.invoke(interpreter)
            closeDirect(interpreter, record, throwCompletion = false)
        }
    }

    /** Iterator.concat 的 helper 状态：按顺序惰性打开各 iterable。 */
    private class ConcatHelperState(
        private val records: List<Pair<JSObject, JSValue>>,
    ) : HelperState {
        override var done: Boolean = false
        override var running: Boolean = false
        override var started: Boolean = false
        private var nextIndex: Int = 0
        private var inner: DirectIterator? = null

        override suspend fun advance(interpreter: Interpreter): JSValue? {
            while (true) {
                val current = inner
                if (current != null) {
                    val value = nextValue(interpreter, current)
                    if (value != null) return value
                    inner = null
                }
                if (nextIndex >= records.size) return null
                val (iterable, method) = records[nextIndex++]
                val iteratorValue = interpreter.callFunction(method, iterable, emptyList())
                val iterator = iteratorValue as? JSObject
                    ?: interpreter.typeError("Iterator method did not return an object")
                inner = getIteratorDirect(interpreter, iterator)
            }
        }

        override suspend fun close(interpreter: Interpreter) {
            inner?.let { closeDirect(interpreter, it, throwCompletion = false) }
        }
    }

    /** Iterator.from 包装器的内部状态（[[Iterated]]）。 */
    private class WrapState(val record: DirectIterator)

    /**
     * Iterator.zip / zipKeyed 的 helper 状态。
     *
     * iters 中 null 表示 longest 模式下已耗尽的槽位，使用 padding 值填充；
     * open 保存仍未耗尽的迭代器，用于按逆序关闭。
     */
    private class ZipHelperState(
        private val iters: MutableList<DirectIterator?>,
        private val padding: List<JSValue>,
        private val mode: String,
        private val keyed: Boolean,
        private val keys: List<PropertyKey>,
        private val intrinsics: Intrinsics,
    ) : HelperState {
        override var done: Boolean = false
        override var running: Boolean = false
        override var started: Boolean = false
        private val open = ArrayList<DirectIterator>()

        init {
            for (iter in iters) {
                if (iter != null) open.add(iter)
            }
        }

        override suspend fun advance(interpreter: Interpreter): JSValue? {
            if (iters.isEmpty()) return null
            val results = ArrayList<JSValue>(iters.size)
            var index = 0
            while (index < iters.size) {
                val iter = iters[index]
                if (iter == null) {
                    results.add(padding.getOrElse(index) { JSUndefined })
                    index++
                    continue
                }
                val value = try {
                    nextValue(interpreter, iter)
                } catch (error: JSException) {
                    // 抛错的迭代器先移出 open，再关闭其余 open 迭代器
                    open.remove(iter)
                    closeOpen(interpreter, throwCompletion = true, cause = error)
                    throw error
                }
                if (value != null) {
                    results.add(value)
                    index++
                    continue
                }
                // 当前迭代器报告 done
                open.remove(iter)
                iters[index] = null
                when (mode) {
                    "shortest" -> {
                        closeOpen(interpreter, throwCompletion = false)
                        return null
                    }

                    "strict" -> {
                        if (index != 0) throwStrictFailure(interpreter)
                        var otherIndex = 1
                        while (otherIndex < iters.size) {
                            val other = iters[otherIndex]
                            if (other != null) {
                                val otherDone = try {
                                    stepDone(interpreter, other)
                                } catch (error: JSException) {
                                    open.remove(other)
                                    closeOpen(interpreter, throwCompletion = true, cause = error)
                                    throw error
                                }
                                if (!otherDone) throwStrictFailure(interpreter)
                                open.remove(other)
                                iters[otherIndex] = null
                            }
                            otherIndex++
                        }
                        return null
                    }

                    else -> results.add(padding.getOrElse(index) { JSUndefined })
                }
                index++
            }
            if (open.isEmpty()) return null
            return finishResults(interpreter, results)
        }

        override suspend fun close(interpreter: Interpreter) {
            closeOpen(interpreter, throwCompletion = false)
        }

        private suspend fun stepDone(interpreter: Interpreter, iter: DirectIterator): Boolean {
            val result = interpreter.callFunction(iter.nextMethod, iter.iterator, emptyList())
            if (result !is JSObject) {
                interpreter.typeError("Iterator result is not an object")
            }
            return interpreter.toBoolean(interpreter.getV(result, PropertyKey.Str("done")))
        }

        /** strict 模式长度不一致：关闭所有 open 迭代器后抛 TypeError。 */
        private suspend fun throwStrictFailure(interpreter: Interpreter): Nothing {
            closeOpen(interpreter, throwCompletion = true)
            interpreter.typeError("Iterator.zip: strict mode iterators have different lengths")
        }

        /**
         * 逆序关闭 open 迭代器。
         * cause 非空时视为 throw 完成（关闭错误忽略并保留原始异常）；
         * 正常完成时记录首个关闭错误，继续关闭其余迭代器后抛出。
         */
        private suspend fun closeOpen(
            interpreter: Interpreter,
            throwCompletion: Boolean,
            cause: JSException? = null,
        ) {
            var firstError: JSException? = cause
            var throwMode = throwCompletion || cause != null
            for (index in open.indices.reversed()) {
                try {
                    closeDirect(interpreter, open[index], throwCompletion = throwMode)
                } catch (error: JSException) {
                    if (firstError == null) firstError = error
                    throwMode = true
                }
            }
            open.clear()
            firstError?.let { throw it }
        }

        /** 结果对象：zip 为 Array，zipKeyed 为 null 原型对象。 */
        private fun finishResults(interpreter: Interpreter, results: List<JSValue>): JSValue {
            if (!keyed) {
                return JSArray(
                    results.toMutableList(),
                    prototype = intrinsics.arrayPrototype,
                )
            }
            val objectValue = JSObject(prototype = null)
            for (index in results.indices) {
                objectValue.defineOwnProperty(
                    keys[index],
                    PropertyDescriptor.data(
                        results[index],
                        writable = true,
                        enumerable = true,
                        configurable = true,
                    ),
                )
            }
            return objectValue
        }
    }

    private fun doneResult(interpreter: Interpreter): JSObject =
        createIterResult(interpreter, JSUndefined, done = true)

    private fun createIterResult(
        interpreter: Interpreter,
        value: JSValue,
        done: Boolean,
    ): JSObject {
        val result = JSObject(prototype = interpreter.intrinsics.objectPrototype)
        result.defineDataProperty(
            PropertyKey.Str("value"), value, writable = true, enumerable = true, configurable = true,
        )
        result.defineDataProperty(
            PropertyKey.Str("done"), JSBoolean.getJsBoolean(done),
            writable = true, enumerable = true, configurable = true,
        )
        return result
    }

    /** GetIteratorDirect：只读取一次 next，可调用性在 IteratorStepValue 调用时自然报错。 */
    private suspend fun getIteratorDirect(interpreter: Interpreter, value: JSValue): DirectIterator {
        if (value !is JSObject) {
            interpreter.typeError("Iterator helper called on non-object")
        }
        val next = interpreter.getV(value, PropertyKey.Str("next"))
        return DirectIterator(value, next)
    }

    /** GetMethod：undefined/null 返回 null，其他非可调用值抛 TypeError。 */
    private suspend fun getMethod(
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

    /**
     * GetIteratorFlattenable：对象优先走 @@iterator，无该方法时把自身视为迭代器；
     * 原始值只有字符串可按策略创建迭代器。
     */
    private suspend fun getIteratorFlattenable(
        interpreter: Interpreter,
        value: JSValue,
        rejectStrings: Boolean,
    ): DirectIterator {
        if (value !is JSObject) {
            if (rejectStrings || value !is JSString) {
                interpreter.typeError("Iterator.from argument is not iterable")
            }
            val stringIteratorMethod = getMethod(
                interpreter,
                interpreter.intrinsics.stringPrototype,
                PropertyKey.Sym(interpreter.intrinsics.symbolIterator),
            ) ?: interpreter.typeError("String is not iterable")
            val result = interpreter.callFunction(stringIteratorMethod, value, emptyList())
            val iterator = result as? JSObject
                ?: interpreter.typeError("Iterator method did not return an object")
            return getIteratorDirect(interpreter, iterator)
        }
        val method = getMethod(
            interpreter,
            value,
            PropertyKey.Sym(interpreter.intrinsics.symbolIterator),
        )
        if (method == null) return getIteratorDirect(interpreter, value)
        val result = interpreter.callFunction(method, value, emptyList())
        val iterator = result as? JSObject
            ?: interpreter.typeError("Iterator method did not return an object")
        return getIteratorDirect(interpreter, iterator)
    }

    /** 读取下一条值；完成时返回 null。 */
    private suspend fun nextValue(interpreter: Interpreter, record: DirectIterator): JSValue? {
        val result = interpreter.callFunction(record.nextMethod, record.iterator, emptyList())
        if (result !is JSObject) {
            interpreter.typeError("Iterator result is not an object")
        }
        if (interpreter.toBoolean(interpreter.getV(result, PropertyKey.Str("done")))) {
            return null
        }
        return interpreter.getV(result, PropertyKey.Str("value"))
    }

    /** IteratorClose：normal 完成要求 return 结果为对象；throwCompletion 忽略关闭错误。 */
    private suspend fun closeDirect(
        interpreter: Interpreter,
        record: DirectIterator,
        throwCompletion: Boolean,
    ) {
        val returnMethod = try {
            interpreter.getV(record.iterator, PropertyKey.Str("return"))
        } catch (error: JSException) {
            if (throwCompletion) return else throw error
        }
        if (returnMethod is JSUndefined || returnMethod is JSNull) return
        if (!interpreter.isCallable(returnMethod)) {
            if (throwCompletion) return
            interpreter.typeError("Iterator return is not a function")
        }
        val result = try {
            interpreter.callFunction(returnMethod, record.iterator, emptyList())
        } catch (error: JSException) {
            if (throwCompletion) return else throw error
        }
        if (!throwCompletion && result !is JSObject) {
            interpreter.typeError("Iterator return result is not an object")
        }
    }

    /**
     * 参数校验失败时的 IfAbruptCloseIterator：
     * 直接对 this 调用 return，不读取 next，关闭错误被吞掉后抛出原始验证错误。
     */
    private suspend fun closeForInvalidArgument(interpreter: Interpreter, thisValue: JSValue) {
        val record = thisValue as? JSObject ?: return
        closeDirect(interpreter, DirectIterator(record, JSUndefined), throwCompletion = true)
    }

    /** 创建辅助迭代器：next/return 由共享原型方法分发到内部状态。 */
    private fun createHelper(
        interpreter: Interpreter,
        record: DirectIterator,
        onReturn: (suspend (Interpreter) -> Unit)? = null,
        advance: suspend (Interpreter) -> JSValue?,
    ): JSObject {
        val helper = JSObject(prototype = interpreter.intrinsics.iteratorHelperPrototype)
        helper.internalState = ProducerHelperState(record, advance, onReturn)
        return helper
    }

    /** 内建迭代器的内部状态：存放在 JSObject.internalState，不暴露给脚本。 */
    private interface NextState {
        suspend fun next(
            interpreter: Interpreter,
            receiver: JSObject,
            args: List<JSValue>,
        ): JSValue
    }

    /**
     * 创建内建迭代器（Array/String/Map/Set/RegExp）：next 位于共享原型，
     * 实例只保存内部状态；生产者返回 null 表示完成，此后固定返回 done。
     */
    fun createIteratorWithNext(
        intrinsics: Intrinsics,
        prototype: JSObject,
        next: suspend (Interpreter, JSValue, List<JSValue>) -> JSValue?,
    ): JSObject {
        val iterator = JSObject(prototype = prototype)
        iterator.internalState = object : NextState {
            private var done: Boolean = false
            override suspend fun next(
                interpreter: Interpreter,
                receiver: JSObject,
                args: List<JSValue>,
            ): JSValue {
                if (done) return doneResult(interpreter)
                val result = next(interpreter, receiver, args) ?: run {
                    done = true
                    return doneResult(interpreter)
                }
                return result
            }
        }
        return iterator
    }

    /** 数组迭代器种类：keys / values / entries。 */
    enum class ArrayIteratorKind { KEYS, VALUES, ENTRIES }

    /**
     * 创建数组迭代器：按 [[NextIndex]] 实时读取目标对象的 length 与元素，
     * 支持 arguments 等 array-like 对象在迭代期间的长度变化。
     */
    fun createArrayIterator(
        intrinsics: Intrinsics,
        target: JSObject,
        kind: ArrayIteratorKind,
    ): JSObject {
        var index = 0L
        return createIteratorWithNext(intrinsics, intrinsics.arrayIteratorPrototype) { interpreter, _, _ ->
            val length = arrayLikeLength(interpreter, target)
            if (index >= length) return@createIteratorWithNext null
            val current = index
            index += 1
            val key = PropertyKey.Str(current.toString())
            val value = when (kind) {
                ArrayIteratorKind.KEYS -> JSNumber(current.toDouble())
                ArrayIteratorKind.VALUES -> interpreter.getV(target, key)
                ArrayIteratorKind.ENTRIES -> JSArray(
                    mutableListOf<JSValue?>(
                        JSNumber(current.toDouble()),
                        interpreter.getV(target, key),
                    ),
                    prototype = intrinsics.arrayPrototype,
                )
            }
            createIterResult(interpreter, value, done = false)
        }
    }

    /** 创建字符串迭代器：按码点步进（代理对作为一个元素）。 */
    fun createStringIterator(intrinsics: Intrinsics, text: String): JSObject {
        var index = 0
        return createIteratorWithNext(intrinsics, intrinsics.stringIteratorPrototype) { interpreter, _, _ ->
            if (index >= text.length) return@createIteratorWithNext null
            val current = text[index]
            val value = if (current.isHighSurrogate() && index + 1 < text.length &&
                text[index + 1].isLowSurrogate()
            ) {
                val pair = text.substring(index, index + 2)
                index += 2
                JSString(pair)
            } else {
                index += 1
                JSString(current.toString())
            }
            createIterResult(interpreter, value, done = false)
        }
    }

    /** LengthOfArrayLike：NaN/负数取 0，向上限 2^53-1 截断。 */
    private suspend fun arrayLikeLength(interpreter: Interpreter, target: JSObject): Long {
        val number = interpreter.toNumber(interpreter.getV(target, PropertyKey.LENGTH))
        return when {
            number.isNaN() || number <= 0.0 -> 0L
            number >= 9007199254740991.0 -> 9007199254740991L
            else -> kotlin.math.floor(number).toLong()
        }
    }

    /** 安装 Iterator 构造器、%IteratorPrototype% 与全部 helper 方法。 */
    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val prototype = intrinsics.iteratorPrototype

        prototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolIterator),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.iterator]", 0) { _, thisValue, _ ->
                thisValue
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        prototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolDispose),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.dispose]", 0) { interpreter, thisValue, _ ->
                val iterator = thisValue as? JSObject
                    ?: interpreter.typeError("Iterator dispose called on non-object")
                val returnMethod = getMethod(interpreter, iterator, PropertyKey.Str("return"))
                if (returnMethod != null) {
                    interpreter.callFunction(returnMethod, iterator, emptyList())
                }
                JSUndefined
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        val iteratorConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "Iterator",
            length = 0,
            prototypeObject = prototype,
        ) { interpreter, thisValue, _ ->
            // 抽象类：直接调用/构造 Iterator 抛错；子类 super() 复用预创建实例
            if (!interpreter.nativeConstructCall || thisValue !is JSObject) {
                interpreter.typeError("Abstract class Iterator not directly constructable")
            }
            thisValue
        }
        intrinsics.iteratorConstructor = iteratorConstructor

        defineIteratorPrototypeAccessors(registry, iteratorConstructor)

        defineIteratorTag(intrinsics, intrinsics.arrayIteratorPrototype, "Array Iterator")
        defineIteratorTag(intrinsics, intrinsics.stringIteratorPrototype, "String Iterator")
        defineIteratorTag(intrinsics, intrinsics.mapIteratorPrototype, "Map Iterator")
        defineIteratorTag(intrinsics, intrinsics.setIteratorPrototype, "Set Iterator")
        defineIteratorTag(intrinsics, intrinsics.regexpStringIteratorPrototype, "RegExp String Iterator")
        defineIteratorTag(intrinsics, intrinsics.iteratorHelperPrototype, "Iterator Helper")

        installIteratorNext(intrinsics)

        intrinsics.iteratorHelperPrototype.defineDataProperty(
            key = PropertyKey.Str("next"),
            value = BuiltinHelpers.method(intrinsics, "next", 0) { interpreter, thisValue, _ ->
                val state = (thisValue as? JSObject)?.internalState as? HelperState
                    ?: interpreter.typeError("next method called on incompatible receiver")
                if (state.running) interpreter.typeError("Iterator Helper is already running")
                if (state.done) return@method doneResult(interpreter)
                state.running = true
                try {
                    val value = try {
                        state.advance(interpreter)
                    } catch (error: JSException) {
                        // 生成器因异常而完成，后续 next 直接返回 done
                        state.done = true
                        throw error
                    }
                    if (value == null) {
                        state.done = true
                        doneResult(interpreter)
                    } else {
                        state.started = true
                        createIterResult(interpreter, value, done = false)
                    }
                } finally {
                    state.running = false
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        intrinsics.iteratorHelperPrototype.defineDataProperty(
            key = PropertyKey.Str("return"),
            value = BuiltinHelpers.method(intrinsics, "return", 0) { interpreter, thisValue, _ ->
                val state = (thisValue as? JSObject)?.internalState as? HelperState
                    ?: interpreter.typeError("return method called on incompatible receiver")
                if (state.running) interpreter.typeError("Iterator Helper is already running")
                if (state.done) return@method doneResult(interpreter)
                state.done = true
                if (state.started) {
                    // suspended-yield：恢复执行期间再次调用 next/return 应抛 TypeError
                    state.running = true
                    try {
                        state.close(interpreter)
                    } finally {
                        state.running = false
                    }
                } else {
                    // suspended-start：先标记 completed，再关闭底层迭代器
                    state.close(interpreter)
                }
                doneResult(interpreter)
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        intrinsics.wrapForValidIteratorPrototype.defineDataProperty(
            key = PropertyKey.Str("next"),
            value = BuiltinHelpers.method(intrinsics, "next", 0) { interpreter, thisValue, _ ->
                val state = (thisValue as? JSObject)?.internalState as? WrapState
                    ?: interpreter.typeError("next method called on incompatible receiver")
                val result = interpreter.callFunction(
                    state.record.nextMethod, state.record.iterator, emptyList(),
                )
                if (result !is JSObject) {
                    interpreter.typeError("Iterator result is not an object")
                }
                result
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        intrinsics.wrapForValidIteratorPrototype.defineDataProperty(
            key = PropertyKey.Str("return"),
            value = BuiltinHelpers.method(intrinsics, "return", 0) { interpreter, thisValue, _ ->
                val state = (thisValue as? JSObject)?.internalState as? WrapState
                    ?: interpreter.typeError("return method called on incompatible receiver")
                val returnMethod = getMethod(
                    interpreter, state.record.iterator, PropertyKey.Str("return"),
                )
                if (returnMethod == null) return@method doneResult(interpreter)
                val result = interpreter.callFunction(
                    returnMethod, state.record.iterator, emptyList(),
                )
                if (result !is JSObject) {
                    interpreter.typeError("Iterator return result is not an object")
                }
                result
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        iteratorConstructor.defineDataProperty(
            key = PropertyKey.Str("from"),
            value = BuiltinHelpers.method(intrinsics, "from", 1) { interpreter, _, args ->
                iteratorFrom(interpreter, args.getOrElse(0) { JSUndefined })
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        iteratorConstructor.defineDataProperty(
            key = PropertyKey.Str("concat"),
            value = BuiltinHelpers.method(intrinsics, "concat", 0) { interpreter, _, args ->
                iteratorConcat(interpreter, args)
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        iteratorConstructor.defineDataProperty(
            key = PropertyKey.Str("zip"),
            value = BuiltinHelpers.method(intrinsics, "zip", 1) { interpreter, _, args ->
                iteratorZip(interpreter, args, keyed = false)
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        iteratorConstructor.defineDataProperty(
            key = PropertyKey.Str("zipKeyed"),
            value = BuiltinHelpers.method(intrinsics, "zipKeyed", 1) { interpreter, _, args ->
                iteratorZip(interpreter, args, keyed = true)
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        installPrototypeMethods(registry)
        registry.defineGlobal("Iterator", iteratorConstructor)
    }

    /** 定义 %IteratorPrototype% 的 constructor 与 @@toStringTag 访问器。 */
    private fun defineIteratorPrototypeAccessors(
        registry: BuiltinRegistry,
        iteratorConstructor: JSNativeFunction,
    ) {
        val intrinsics = registry.intrinsics
        val prototype = intrinsics.iteratorPrototype
        val constructorKey = PropertyKey.CONSTRUCTOR
        val tagKey = PropertyKey.Sym(intrinsics.symbolToStringTag)

        // 覆盖 JSNativeFunction 构造时写入的普通 constructor 数据属性
        prototype.defineAccessorProperty(
            key = constructorKey,
            get = BuiltinHelpers.method(intrinsics, "get constructor", 0) { _, _, _ ->
                iteratorConstructor
            },
            set = BuiltinHelpers.method(intrinsics, "set constructor", 1) { interpreter, thisValue, args ->
                setterThatIgnoresPrototypeProperties(
                    interpreter,
                    thisValue,
                    prototype,
                    constructorKey,
                    args.getOrElse(0) { JSUndefined },
                )
                JSUndefined
            },
            enumerable = false,
            configurable = true,
        )
        prototype.defineAccessorProperty(
            key = tagKey,
            get = BuiltinHelpers.method(intrinsics, "get [Symbol.toStringTag]", 0) { _, _, _ ->
                JSString("Iterator")
            },
            set = BuiltinHelpers.method(intrinsics, "set [Symbol.toStringTag]", 1) { interpreter, thisValue, args ->
                setterThatIgnoresPrototypeProperties(
                    interpreter,
                    thisValue,
                    prototype,
                    tagKey,
                    args.getOrElse(0) { JSUndefined },
                )
                JSUndefined
            },
            enumerable = false,
            configurable = true,
        )
    }

    /** 定义内建迭代器原型上的 @@toStringTag 数据属性。 */
    private fun defineIteratorTag(intrinsics: Intrinsics, prototype: JSObject, tag: String) {
        prototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString(tag),
            writable = false,
            enumerable = false,
            configurable = true,
        )
    }

    /**
     * 在各内建迭代器原型上安装共享 next：
     * 通过 internalState 做品牌校验并取得 [[NextIndex]] 等内部状态。
     */
    private fun installIteratorNext(intrinsics: Intrinsics) {
        val nextMethod = BuiltinHelpers.method(intrinsics, "next", 0) { interpreter, thisValue, _ ->
            val iterator = thisValue as? JSObject
                ?: interpreter.typeError("next method called on incompatible receiver")
            val state = iterator.internalState as? NextState
                ?: interpreter.typeError("next method called on incompatible receiver")
            state.next(interpreter, iterator, emptyList())
        }
        for (prototype in listOf(
            intrinsics.arrayIteratorPrototype,
            intrinsics.stringIteratorPrototype,
            intrinsics.mapIteratorPrototype,
            intrinsics.setIteratorPrototype,
            intrinsics.regexpStringIteratorPrototype,
        )) {
            prototype.defineDataProperty(
                key = PropertyKey.Str("next"),
                value = nextMethod,
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }
    }

    /**
     * SetterThatIgnoresPrototypeProperties(home, key, value)：
     * 对 home 自身赋值抛 TypeError；目标无自有属性时创建数据属性，否则按严格模式 Set。
     */
    private suspend fun setterThatIgnoresPrototypeProperties(
        interpreter: Interpreter,
        thisValue: JSValue,
        home: JSObject,
        key: PropertyKey,
        value: JSValue,
    ) {
        if (thisValue !is JSObject) {
            interpreter.typeError("Iterator prototype setter called on non-object")
        }
        if (thisValue === home) {
            interpreter.typeError("Cannot assign to read only property '${key}'")
        }
        if (thisValue.getOwnProperty(key) == null) {
            val defined = interpreter.defineOwnPropertyOn(
                thisValue,
                key,
                PropertyDescriptor.data(
                    value,
                    writable = true,
                    enumerable = true,
                    configurable = true,
                ),
            )
            if (!defined) interpreter.typeError("Cannot create property '${key}'")
        } else {
            interpreter.putV(thisValue, key, value, strict = true)
        }
    }

    private suspend fun iteratorFrom(interpreter: Interpreter, value: JSValue): JSValue {
        val record = getIteratorFlattenable(interpreter, value, rejectStrings = false)
        // 已是 Iterator 实例时原样返回（OrdinaryHasInstance(%Iterator%, O)）
        if (value is JSObject &&
            isIteratorInstance(value, interpreter.intrinsics.iteratorPrototype)
        ) {
            return value
        }
        return createWrapper(interpreter, record)
    }

    /**
     * Iterator.concat：先对所有参数做对象与 @@iterator 校验，
     * 返回惰性打开的 Iterator Helper。
     */
    private suspend fun iteratorConcat(
        interpreter: Interpreter,
        args: List<JSValue>,
    ): JSObject {
        val records = ArrayList<Pair<JSObject, JSValue>>(args.size)
        for (item in args) {
            if (item !is JSObject) {
                interpreter.typeError("Iterator.concat argument is not an object")
            }
            val method = getMethod(
                interpreter,
                item,
                PropertyKey.Sym(interpreter.intrinsics.symbolIterator),
            ) ?: interpreter.typeError("Iterator.concat argument is not iterable")
            records.add(item to method)
        }
        val helper = JSObject(prototype = interpreter.intrinsics.iteratorHelperPrototype)
        helper.internalState = ConcatHelperState(records)
        return helper
    }

    /** 读取对象的 @@iterator 并创建直接迭代器记录。 */
    private suspend fun iteratorFromIterable(
        interpreter: Interpreter,
        objectValue: JSObject,
        description: String,
    ): DirectIterator {
        val method = getMethod(
            interpreter,
            objectValue,
            PropertyKey.Sym(interpreter.intrinsics.symbolIterator),
        ) ?: interpreter.typeError("$description is not iterable")
        val iteratorValue = interpreter.callFunction(method, objectValue, emptyList())
        val iterator = iteratorValue as? JSObject
            ?: interpreter.typeError("Iterator method did not return an object")
        return getIteratorDirect(interpreter, iterator)
    }

    /** Iterator.zip / zipKeyed：校验选项、收集迭代器、预读 padding 后返回 helper。 */
    private suspend fun iteratorZip(
        interpreter: Interpreter,
        args: List<JSValue>,
        keyed: Boolean,
    ): JSObject {
        val iterablesArg = args.getOrElse(0) { JSUndefined }
        if (iterablesArg !is JSObject) {
            interpreter.typeError("Iterator.zip requires an object")
        }
        val optionsArg = args.getOrElse(1) { JSUndefined }
        val options = when (optionsArg) {
            is JSUndefined -> JSObject(prototype = null)
            is JSObject -> optionsArg
            else -> interpreter.typeError("options must be an object")
        }
        val modeValue = interpreter.getV(options, PropertyKey.Str("mode"))
        val mode = if (modeValue is JSUndefined) {
            "shortest"
        } else if (modeValue is JSString &&
            modeValue.value in setOf("shortest", "longest", "strict")
        ) {
            modeValue.value
        } else {
            interpreter.typeError("Invalid mode")
        }
        var paddingOption: JSValue = JSUndefined
        if (mode == "longest") {
            paddingOption = interpreter.getV(options, PropertyKey.Str("padding"))
            if (paddingOption !is JSUndefined && paddingOption !is JSObject) {
                interpreter.typeError("padding must be an object")
            }
        }

        val iters = ArrayList<DirectIterator?>()
        val keys = ArrayList<PropertyKey>()
        if (keyed) {
            for (key in iterablesArg.ownPropertyKeys()) {
                val descriptor = iterablesArg.getOwnProperty(key) ?: continue
                if (!descriptor.enumerable) continue
                val value = try {
                    interpreter.getV(iterablesArg, key)
                } catch (error: JSException) {
                    closeZipIters(interpreter, iters, error)
                    throw error
                }
                if (value is JSUndefined) continue
                val iter = try {
                    getIteratorFlattenable(interpreter, value, rejectStrings = true)
                } catch (error: JSException) {
                    closeZipIters(interpreter, iters, error)
                    throw error
                }
                keys.add(key)
                iters.add(iter)
            }
        } else {
            val inputIter = iteratorFromIterable(interpreter, iterablesArg, "iterables")
            while (true) {
                val value = try {
                    nextValue(interpreter, inputIter)
                } catch (error: JSException) {
                    closeZipIters(interpreter, iters, error)
                    throw error
                } ?: break
                val iter = try {
                    getIteratorFlattenable(interpreter, value, rejectStrings = true)
                } catch (error: JSException) {
                    closeZipWithInput(interpreter, iters, inputIter, error)
                    throw error
                }
                iters.add(iter)
            }
        }

        val padding = ArrayList<JSValue>(iters.size)
        if (mode == "longest" && paddingOption !is JSUndefined) {
            if (keyed) {
                // zipKeyed 的 padding 是同键属性包，不是迭代器
                for (key in keys) {
                    val value = try {
                        interpreter.getV(paddingOption as JSObject, key)
                    } catch (error: JSException) {
                        closeZipIters(interpreter, iters, error)
                        throw error
                    }
                    padding.add(value)
                }
            } else {
                val paddingIter = try {
                    iteratorFromIterable(interpreter, paddingOption as JSObject, "padding")
                } catch (error: JSException) {
                    closeZipIters(interpreter, iters, error)
                    throw error
                }
                var usingIterator = true
                try {
                    for (index in iters.indices) {
                        if (usingIterator) {
                            val value = nextValue(interpreter, paddingIter)
                            if (value != null) {
                                padding.add(value)
                                continue
                            }
                            usingIterator = false
                        }
                        padding.add(JSUndefined)
                    }
                } catch (error: JSException) {
                    // padding 迭代出错时只关闭 iters，不调用 padding 的 return
                    closeZipIters(interpreter, iters, error)
                    throw error
                }
                if (usingIterator) {
                    try {
                        closeDirect(interpreter, paddingIter, throwCompletion = false)
                    } catch (error: JSException) {
                        closeZipIters(interpreter, iters, error)
                        throw error
                    }
                }
            }
        } else {
            repeat(iters.size) { padding.add(JSUndefined) }
        }

        val helper = JSObject(prototype = interpreter.intrinsics.iteratorHelperPrototype)
        helper.internalState = ZipHelperState(
            iters = iters,
            padding = padding,
            mode = mode,
            keyed = keyed,
            keys = keys,
            intrinsics = interpreter.intrinsics,
        )
        return helper
    }

    /** 创建阶段错误：逆序关闭已收集迭代器（throw 完成），再抛出原始异常。 */
    private suspend fun closeZipIters(
        interpreter: Interpreter,
        iters: List<DirectIterator?>,
        cause: JSException,
    ) {
        for (index in iters.indices.reversed()) {
            val iter = iters[index] ?: continue
            closeDirect(interpreter, iter, throwCompletion = true)
        }
        throw cause
    }

    /** 输入 iterable 出错：先关闭已收集迭代器，再关闭输入迭代器。 */
    private suspend fun closeZipWithInput(
        interpreter: Interpreter,
        iters: List<DirectIterator?>,
        inputIter: DirectIterator,
        cause: JSException,
    ) {
        for (index in iters.indices.reversed()) {
            val iter = iters[index] ?: continue
            closeDirect(interpreter, iter, throwCompletion = true)
        }
        closeDirect(interpreter, inputIter, throwCompletion = true)
        throw cause
    }

    /** 沿原型链判断实例是否继承自 %IteratorPrototype%。 */
    private fun isIteratorInstance(value: JSObject, iteratorPrototype: JSObject): Boolean {
        var current: JSObject? = value.prototype
        while (current != null) {
            if (current === iteratorPrototype) return true
            current = current.prototype
        }
        return false
    }

    /** %WrapForValidIteratorPrototype% 实例：转发 next/return 到底层迭代器记录。 */
    private fun createWrapper(interpreter: Interpreter, record: DirectIterator): JSObject {
        val wrapper = JSObject(prototype = interpreter.intrinsics.wrapForValidIteratorPrototype)
        wrapper.internalState = WrapState(record)
        return wrapper
    }

    /** chunks/windows 的尺寸：必须是 1..2^32-1 的整数 Number，且不做类型转换。 */
    private suspend fun validateChunkSize(
        interpreter: Interpreter,
        value: JSValue,
        thisValue: JSValue,
    ): Long {
        if (value !is JSNumber) {
            closeForInvalidArgument(interpreter, thisValue)
            interpreter.typeError("Size must be a Number")
        }
        val number = value.value
        if (!number.isFinite() || kotlin.math.floor(number) != number) {
            closeForInvalidArgument(interpreter, thisValue)
            interpreter.typeError("Size must be an integral Number")
        }
        if (number < 1.0 || number > 4294967295.0) {
            closeForInvalidArgument(interpreter, thisValue)
            interpreter.rangeError("Size out of range")
        }
        return number.toLong()
    }

    /** take/drop 的 limit：按规范顺序 ToNumber、范围校验与 ToIntegerOrInfinity。 */
    private suspend fun toLimit(interpreter: Interpreter, value: JSValue, thisValue: JSValue): Double {
        val number = try {
            interpreter.toNumber(value)
        } catch (error: JSException) {
            closeForInvalidArgument(interpreter, thisValue)
            throw error
        }
        if (number.isNaN()) {
            closeForInvalidArgument(interpreter, thisValue)
            interpreter.rangeError("Invalid limit")
        }
        if (number.isFinite() && number > 9007199254740991.0) {
            closeForInvalidArgument(interpreter, thisValue)
            interpreter.rangeError("Invalid limit")
        }
        val integer = if (!number.isFinite()) {
            number
        } else {
            val truncated = kotlin.math.truncate(number)
            if (truncated == 0.0) 0.0 else truncated
        }
        if (integer < 0.0) {
            closeForInvalidArgument(interpreter, thisValue)
            interpreter.rangeError("Invalid limit")
        }
        return integer
    }

    /** 安装 %IteratorPrototype% 上的辅助方法。 */
    private fun installPrototypeMethods(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val prototype = intrinsics.iteratorPrototype

        prototype.defineDataProperty(
            PropertyKey.Str("chunks"),
            BuiltinHelpers.method(intrinsics, "chunks", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.chunks called on non-object")
                }
                val chunkSize = validateChunkSize(
                    interpreter,
                    args.getOrElse(0) { JSUndefined },
                    thisValue,
                )
                val record = getIteratorDirect(interpreter, thisValue)
                createHelper(interpreter, record) { inner ->
                    val buffer = ArrayList<JSValue>()
                    while (buffer.size.toLong() < chunkSize) {
                        val value = nextValue(inner, record)
                        if (value == null) {
                            if (buffer.isEmpty()) return@createHelper null
                            return@createHelper JSArray(
                                buffer.toMutableList(),
                                prototype = intrinsics.arrayPrototype,
                            )
                        }
                        buffer.add(value)
                    }
                    JSArray(buffer.toMutableList(), prototype = intrinsics.arrayPrototype)
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("windows"),
            BuiltinHelpers.method(intrinsics, "windows", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.windows called on non-object")
                }
                val windowSize = validateChunkSize(
                    interpreter,
                    args.getOrElse(0) { JSUndefined },
                    thisValue,
                )
                val undersizedValue = args.getOrElse(1) { JSUndefined }
                val undersized = if (undersizedValue is JSUndefined) {
                    "only-full"
                } else if (undersizedValue is JSString &&
                    undersizedValue.value in setOf("only-full", "allow-partial")
                ) {
                    undersizedValue.value
                } else {
                    closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Invalid undersized mode")
                }
                val record = getIteratorDirect(interpreter, thisValue)
                val buffer = ArrayList<JSValue>()
                var yieldedPartial = false
                createHelper(interpreter, record) { inner ->
                    while (true) {
                        val value = nextValue(inner, record)
                        if (value == null) {
                            if (undersized == "allow-partial" && !yieldedPartial &&
                                buffer.isNotEmpty() && buffer.size.toLong() < windowSize
                            ) {
                                yieldedPartial = true
                                return@createHelper JSArray(
                                    buffer.toMutableList(),
                                    prototype = intrinsics.arrayPrototype,
                                )
                            }
                            return@createHelper null
                        }
                        if (buffer.size.toLong() == windowSize) {
                            buffer.removeAt(0)
                        }
                        buffer.add(value)
                        if (buffer.size.toLong() == windowSize) {
                            return@createHelper JSArray(
                                buffer.toMutableList(),
                                prototype = intrinsics.arrayPrototype,
                            )
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("map"),
            BuiltinHelpers.method(intrinsics, "map", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.map called on non-object")
                }
                val mapper = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(mapper)) {
                    closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.map requires a callable mapper")
                }
                val record = getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                createHelper(interpreter, record) { _ ->
                    val value = nextValue(interpreter, record) ?: return@createHelper null
                    val index = counter
                    val mapped = try {
                        interpreter.callFunction(mapper, JSUndefined, listOf(value, JSNumber(index)))
                    } catch (error: JSException) {
                        closeDirect(interpreter, record, throwCompletion = true)
                        throw error
                    }
                    counter += 1.0
                    mapped
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("filter"),
            BuiltinHelpers.method(intrinsics, "filter", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.filter called on non-object")
                }
                val predicate = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(predicate)) {
                    closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.filter requires a callable predicate")
                }
                val record = getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                createHelper(interpreter, record) { _ ->
                    while (true) {
                        val value = nextValue(interpreter, record) ?: return@createHelper null
                        val index = counter
                        val keep = try {
                            interpreter.callFunction(
                                predicate,
                                JSUndefined,
                                listOf(value, JSNumber(index)),
                            )
                        } catch (error: JSException) {
                            closeDirect(interpreter, record, throwCompletion = true)
                            throw error
                        }
                        counter += 1.0
                        if (interpreter.toBoolean(keep)) return@createHelper value
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("take"),
            BuiltinHelpers.method(intrinsics, "take", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.take called on non-object")
                }
                var remaining = toLimit(interpreter, args.getOrElse(0) { JSUndefined }, thisValue)
                val record = getIteratorDirect(interpreter, thisValue)
                createHelper(interpreter, record) { _ ->
                    if (remaining <= 0.0) {
                        // 正常完成关闭底层迭代器：return 结果必须是对象
                        closeDirect(interpreter, record, throwCompletion = false)
                        return@createHelper null
                    }
                    remaining -= 1.0
                    nextValue(interpreter, record)
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("drop"),
            BuiltinHelpers.method(intrinsics, "drop", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.drop called on non-object")
                }
                var remaining = toLimit(interpreter, args.getOrElse(0) { JSUndefined }, thisValue)
                val record = getIteratorDirect(interpreter, thisValue)
                createHelper(interpreter, record) { _ ->
                    while (remaining > 0.0) {
                        remaining -= 1.0
                        if (nextValue(interpreter, record) == null) return@createHelper null
                    }
                    nextValue(interpreter, record)
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("flatMap"),
            BuiltinHelpers.method(intrinsics, "flatMap", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.flatMap called on non-object")
                }
                val mapper = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(mapper)) {
                    closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.flatMap requires a callable mapper")
                }
                val record = getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                var inner: DirectIterator? = null
                createHelper(
                    interpreter,
                    record,
                    onReturn = { innerInterpreter ->
                        inner?.let { closeDirect(innerInterpreter, it, throwCompletion = false) }
                    },
                ) { _ ->
                    while (true) {
                        val currentInner = inner
                        if (currentInner != null) {
                            val innerValue = try {
                                nextValue(interpreter, currentInner)
                            } catch (error: JSException) {
                                // 内层 step 抛错：只关闭外层，内层保持原状
                                closeDirect(interpreter, record, throwCompletion = true)
                                throw error
                            }
                            if (innerValue != null) return@createHelper innerValue
                            inner = null
                        }
                        val value = nextValue(interpreter, record) ?: return@createHelper null
                        val index = counter
                        val mapped = try {
                            interpreter.callFunction(
                                mapper,
                                JSUndefined,
                                listOf(value, JSNumber(index)),
                            )
                        } catch (error: JSException) {
                            closeDirect(interpreter, record, throwCompletion = true)
                            throw error
                        }
                        counter += 1.0
                        val innerRecord = try {
                            getIteratorFlattenable(interpreter, mapped, rejectStrings = true)
                        } catch (error: JSException) {
                            closeDirect(interpreter, record, throwCompletion = true)
                            throw error
                        }
                        inner = innerRecord
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("reduce"),
            BuiltinHelpers.method(intrinsics, "reduce", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.reduce called on non-object")
                }
                val reducer = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(reducer)) {
                    closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.reduce requires a callable reducer")
                }
                val record = getIteratorDirect(interpreter, thisValue)
                val hasInitial = args.size > 1
                var accumulator: JSValue
                var counter: Double
                if (hasInitial) {
                    accumulator = args[1]
                    counter = 0.0
                } else {
                    accumulator = nextValue(interpreter, record)
                        ?: interpreter.typeError(
                            "Reduce of empty iterator with no initial value"
                        )
                    counter = 1.0
                }
                while (true) {
                    val value = nextValue(interpreter, record) ?: return@method accumulator
                    val index = counter
                    accumulator = try {
                        interpreter.callFunction(
                            reducer,
                            JSUndefined,
                            listOf(accumulator, value, JSNumber(index)),
                        )
                    } catch (error: JSException) {
                        closeDirect(interpreter, record, throwCompletion = true)
                        throw error
                    }
                    counter += 1.0
                }
                @Suppress("UNREACHABLE_CODE")
                accumulator
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("toArray"),
            BuiltinHelpers.method(intrinsics, "toArray", 0) { interpreter, thisValue, _ ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.toArray called on non-object")
                }
                val record = getIteratorDirect(interpreter, thisValue)
                val values = ArrayList<JSValue?>()
                while (true) {
                    val value = nextValue(interpreter, record) ?: break
                    values.add(value)
                }
                JSArray(values, prototype = intrinsics.arrayPrototype)
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("forEach"),
            BuiltinHelpers.method(intrinsics, "forEach", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.forEach called on non-object")
                }
                val callback = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(callback)) {
                    closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.forEach requires a callable callback")
                }
                val record = getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                while (true) {
                    val value = nextValue(interpreter, record) ?: break
                    try {
                        interpreter.callFunction(
                            callback,
                            JSUndefined,
                            listOf(value, JSNumber(counter)),
                        )
                    } catch (error: JSException) {
                        closeDirect(interpreter, record, throwCompletion = true)
                        throw error
                    }
                    counter += 1.0
                }
                JSUndefined
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("some"),
            BuiltinHelpers.method(intrinsics, "some", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.some called on non-object")
                }
                val predicate = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(predicate)) {
                    closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.some requires a callable predicate")
                }
                val record = getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                while (true) {
                    val value = nextValue(interpreter, record) ?: return@method JSBoolean.FALSE
                    val matched = try {
                        interpreter.callFunction(
                            predicate,
                            JSUndefined,
                            listOf(value, JSNumber(counter)),
                        )
                    } catch (error: JSException) {
                        closeDirect(interpreter, record, throwCompletion = true)
                        throw error
                    }
                    counter += 1.0
                    if (interpreter.toBoolean(matched)) {
                        closeDirect(interpreter, record, throwCompletion = false)
                        return@method JSBoolean.TRUE
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                JSBoolean.FALSE
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("every"),
            BuiltinHelpers.method(intrinsics, "every", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.every called on non-object")
                }
                val predicate = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(predicate)) {
                    closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.every requires a callable predicate")
                }
                val record = getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                while (true) {
                    val value = nextValue(interpreter, record) ?: return@method JSBoolean.TRUE
                    val matched = try {
                        interpreter.callFunction(
                            predicate,
                            JSUndefined,
                            listOf(value, JSNumber(counter)),
                        )
                    } catch (error: JSException) {
                        closeDirect(interpreter, record, throwCompletion = true)
                        throw error
                    }
                    counter += 1.0
                    if (!interpreter.toBoolean(matched)) {
                        closeDirect(interpreter, record, throwCompletion = false)
                        return@method JSBoolean.FALSE
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                JSBoolean.TRUE
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("find"),
            BuiltinHelpers.method(intrinsics, "find", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Iterator.prototype.find called on non-object")
                }
                val predicate = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(predicate)) {
                    closeForInvalidArgument(interpreter, thisValue)
                    interpreter.typeError("Iterator.prototype.find requires a callable predicate")
                }
                val record = getIteratorDirect(interpreter, thisValue)
                var counter = 0.0
                while (true) {
                    val value = nextValue(interpreter, record) ?: return@method JSUndefined
                    val matched = try {
                        interpreter.callFunction(
                            predicate,
                            JSUndefined,
                            listOf(value, JSNumber(counter)),
                        )
                    } catch (error: JSException) {
                        closeDirect(interpreter, record, throwCompletion = true)
                        throw error
                    }
                    counter += 1.0
                    if (interpreter.toBoolean(matched)) {
                        closeDirect(interpreter, record, throwCompletion = false)
                        return@method value
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                JSUndefined
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
    }
}
