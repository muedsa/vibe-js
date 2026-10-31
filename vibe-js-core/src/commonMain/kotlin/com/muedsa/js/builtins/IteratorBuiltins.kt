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

    /** 辅助迭代器状态。 */
    private class HelperState(val record: DirectIterator) {
        var done: Boolean = false
        var running: Boolean = false
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

    /** 创建辅助迭代器：next 推进值，return 转发到被包装迭代器。 */
    private fun createHelper(
        interpreter: Interpreter,
        record: DirectIterator,
        onReturn: (suspend (Interpreter) -> Unit)? = null,
        advance: suspend (HelperState) -> JSValue?,
    ): JSObject {
        val state = HelperState(record)
        val helper = JSObject(prototype = interpreter.intrinsics.iteratorHelperPrototype)
        helper.defineDataProperty(
            PropertyKey.Str("next"),
            JSNativeFunction(
                name = "next",
                length = 0,
                prototype = interpreter.intrinsics.functionPrototype,
            ) { inner, thisValue, _ ->
                if (thisValue !== helper) {
                    inner.typeError("Iterator Helper next called on incompatible receiver")
                }
                if (state.done) return@JSNativeFunction doneResult(inner)
                if (state.running) {
                    // 对应 GeneratorValidate 的 executing 状态检查
                    inner.typeError("Iterator Helper is already running")
                }
                state.running = true
                try {
                    val value = try {
                        advance(state)
                    } catch (error: JSException) {
                        // 生成器因异常而完成，后续 next 直接返回 done
                        state.done = true
                        throw error
                    }
                    if (value == null) {
                        state.done = true
                        doneResult(inner)
                    } else {
                        createIterResult(inner, value, done = false)
                    }
                } finally {
                    state.running = false
                }
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        helper.defineDataProperty(
            PropertyKey.Str("return"),
            JSNativeFunction(
                name = "return",
                length = 0,
                prototype = interpreter.intrinsics.functionPrototype,
            ) { inner, thisValue, _ ->
                if (thisValue !== helper) {
                    inner.typeError("Iterator Helper return called on incompatible receiver")
                }
                if (state.done) return@JSNativeFunction doneResult(inner)
                state.done = true
                onReturn?.invoke(inner)
                closeDirect(inner, record, throwCompletion = false)
                doneResult(inner)
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
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

        for (helperPrototype in listOf(
            intrinsics.iteratorHelperPrototype,
            intrinsics.wrapForValidIteratorPrototype,
        )) {
            helperPrototype.defineDataProperty(
                key = PropertyKey.Str("next"),
                value = BuiltinHelpers.method(intrinsics, "next", 0) { interpreter, _, _ ->
                    interpreter.typeError("next method called on incompatible receiver")
                },
                writable = true,
                enumerable = false,
                configurable = true,
            )
            helperPrototype.defineDataProperty(
                key = PropertyKey.Str("return"),
                value = BuiltinHelpers.method(intrinsics, "return", 0) { interpreter, _, _ ->
                    interpreter.typeError("return method called on incompatible receiver")
                },
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }

        iteratorConstructor.defineDataProperty(
            key = PropertyKey.Str("from"),
            value = BuiltinHelpers.method(intrinsics, "from", 1) { interpreter, _, args ->
                iteratorFrom(interpreter, args.getOrElse(0) { JSUndefined })
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
        wrapper.defineDataProperty(
            PropertyKey.Str("next"),
            JSNativeFunction(
                name = "next",
                length = 0,
                prototype = interpreter.intrinsics.functionPrototype,
            ) { inner, thisValue, _ ->
                if (thisValue !== wrapper) {
                    inner.typeError("next method called on incompatible receiver")
                }
                val result = inner.callFunction(record.nextMethod, record.iterator, emptyList())
                if (result !is JSObject) {
                    inner.typeError("Iterator result is not an object")
                }
                result
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        wrapper.defineDataProperty(
            PropertyKey.Str("return"),
            JSNativeFunction(
                name = "return",
                length = 0,
                prototype = interpreter.intrinsics.functionPrototype,
            ) { inner, thisValue, _ ->
                if (thisValue !== wrapper) {
                    inner.typeError("return method called on incompatible receiver")
                }
                val returnMethod = getMethod(inner, record.iterator, PropertyKey.Str("return"))
                if (returnMethod == null) {
                    return@JSNativeFunction doneResult(inner)
                }
                val result = inner.callFunction(returnMethod, record.iterator, emptyList())
                if (result !is JSObject) {
                    inner.typeError("Iterator return result is not an object")
                }
                result
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        return wrapper
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
                createHelper(interpreter, record) { state ->
                    if (remaining <= 0.0) {
                        // 正常完成关闭底层迭代器：return 结果必须是对象
                        state.done = true
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
                ) { state ->
                    while (true) {
                        val currentInner = inner
                        if (currentInner != null) {
                            val innerValue = try {
                                nextValue(interpreter, currentInner)
                            } catch (error: JSException) {
                                // 内层 step 抛错：只关闭外层，内层保持原状
                                state.done = true
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
                            state.done = true
                            closeDirect(interpreter, record, throwCompletion = true)
                            throw error
                        }
                        counter += 1.0
                        val innerRecord = try {
                            getIteratorFlattenable(interpreter, mapped, rejectStrings = true)
                        } catch (error: JSException) {
                            state.done = true
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
