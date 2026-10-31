package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArguments
import com.muedsa.js.runtime.value.JSBoxedPrimitive
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSRegExp
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSSymbol
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyDescriptor
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Object 构造器与 Object.prototype。
 */
object ObjectBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val objectPrototype = intrinsics.objectPrototype

        // Object 构造器：对象原样返回，原始值转为包装对象
        val objectConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "Object",
            length = 1,
            prototypeObject = objectPrototype,
        ) { interpreter, _, args ->
            val value = args.getOrNull(0) ?: JSUndefined
            // 规范：无参或 undefined/null 时创建普通新对象；对象原样返回，原始值装箱
            when (value) {
                is JSObject -> value
                is JSUndefined, is JSNull -> JSObject(prototype = objectPrototype)
                else -> interpreter.toObject(value)
            }
        }

        installPrototypeMethods(registry, objectConstructor)
        installStaticMethods(registry, objectConstructor)

        registry.defineGlobal("Object", objectConstructor)
        registry.defineIntrinsic("Object.prototype", objectPrototype)
    }

    private fun installPrototypeMethods(registry: BuiltinRegistry, objectConstructor: JSNativeFunction) {
        val intrinsics = registry.intrinsics
        val objectPrototype = intrinsics.objectPrototype

        objectPrototype.defineDataProperty(
            key = PropertyKey.Str("hasOwnProperty"),
            value = BuiltinHelpers.method(intrinsics, "hasOwnProperty", 1) { interpreter, thisValue, args ->
                // 规范顺序：先 ToPropertyKey(V)，再 ToObject(O)
                val key = interpreter.toPropertyKey(args.getOrElse(0) { JSUndefined })
                val objectValue = interpreter.toObject(thisValue)
                JSBoolean.getJsBoolean(objectValue.hasOwnProperty(key))
            },
        )

        objectPrototype.defineDataProperty(
            key = PropertyKey.Str("isPrototypeOf"),
            value = BuiltinHelpers.method(intrinsics, "isPrototypeOf", 1) { _, thisValue, args ->
                val target = args.getOrNull(0)
                if (thisValue !is JSObject || target !is JSObject) {
                    JSBoolean.FALSE
                } else {
                    var prototype = target.prototype
                    var result = false
                    while (prototype != null) {
                        if (prototype === thisValue) {
                            result = true
                            break
                        }
                        prototype = prototype.prototype
                    }
                    JSBoolean.getJsBoolean(result)
                }
            },
        )

        objectPrototype.defineDataProperty(
            key = PropertyKey.Str("propertyIsEnumerable"),
            value = BuiltinHelpers.method(intrinsics, "propertyIsEnumerable", 1) { interpreter, thisValue, args ->
                val key = interpreter.toPropertyKey(args.getOrElse(0) { JSUndefined })
                val objectValue = interpreter.toObject(thisValue)
                JSBoolean.getJsBoolean(objectValue.getOwnProperty(key)?.enumerable == true)
            },
        )

        objectPrototype.defineDataProperty(
            key = PropertyKey.Str("valueOf"),
            value = BuiltinHelpers.method(intrinsics, "valueOf", 0) { interpreter, thisValue, _ ->
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("Object.prototype.valueOf called on null or undefined")
                }
                if (thisValue is JSObject) thisValue else interpreter.toObject(thisValue)
            },
        )

        objectPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            value = BuiltinHelpers.method(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
                if (thisValue is JSUndefined) return@method JSString("[object Undefined]")
                if (thisValue is JSNull) return@method JSString("[object Null]")
                // 原始值按 ToObject 读取 @@toStringTag（Symbol 包装等）
                val objectValue = interpreter.toObject(thisValue)
                val tagValue = interpreter.getV(
                    objectValue,
                    PropertyKey.Sym(intrinsics.symbolToStringTag),
                )
                val tag = if (tagValue is JSString) {
                    tagValue.value
                } else {
                    objectTag(intrinsics, thisValue)
                }
                JSString("[object $tag]")
            },
        )

        objectPrototype.defineDataProperty(
            key = PropertyKey.Str("toLocaleString"),
            value = BuiltinHelpers.method(intrinsics, "toLocaleString", 0) { interpreter, thisValue, _ ->
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("Object.prototype.toLocaleString called on null or undefined")
                }
                val objectValue = interpreter.toObject(thisValue)
                val method = interpreter.getV(objectValue, PropertyKey.Str("toString"))
                if (!interpreter.isCallable(method)) {
                    interpreter.typeError("toString is not a function")
                }
                interpreter.callFunction(method, objectValue, emptyList())
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        // __proto__ 访问器（常见写法；规范中位于 Object.prototype）
        objectPrototype.defineAccessorProperty(
            key = PropertyKey.Str("__proto__"),
            get = BuiltinHelpers.method(intrinsics, "get __proto__", 0) { _, thisValue, _ ->
                (thisValue as? JSObject)?.prototype ?: JSNull
            },
            set = BuiltinHelpers.method(intrinsics, "set __proto__", 1) { interpreter, thisValue, args ->
                val target = thisValue
                val newPrototype = args.getOrNull(0)
                if (target is JSObject && (newPrototype is JSObject || newPrototype is JSNull)) {
                    // 不可扩展对象不能修改原型；规范 setter 在失败时抛 TypeError
                    if (!target.isExtensible() && target.prototype !== newPrototype) {
                        interpreter.typeError("Cannot set prototype of non-extensible object")
                    }
                    target.prototype = newPrototype as? JSObject
                }
                JSUndefined
            },
            enumerable = false,
            configurable = true,
        )

        // Annex B 访问器方法：__defineGetter__/__defineSetter__/__lookupGetter__/__lookupSetter__
        objectPrototype.defineDataProperty(
            key = PropertyKey.Str("__defineGetter__"),
            value = BuiltinHelpers.method(intrinsics, "__defineGetter__", 2) { interpreter, thisValue, args ->
                val objectValue = interpreter.toObject(thisValue)
                val getter = args.getOrElse(1) { JSUndefined }
                if (!interpreter.isCallable(getter)) {
                    interpreter.typeError("__defineGetter__ requires a callable getter")
                }
                val key = interpreter.toPropertyKey(args.getOrElse(0) { JSUndefined })
                defineAccessorPropertyOrThrow(
                    interpreter, objectValue, key, getter, null,
                    preserveGet = false, preserveSet = true,
                )
                JSUndefined
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        objectPrototype.defineDataProperty(
            key = PropertyKey.Str("__defineSetter__"),
            value = BuiltinHelpers.method(intrinsics, "__defineSetter__", 2) { interpreter, thisValue, args ->
                val objectValue = interpreter.toObject(thisValue)
                val setter = args.getOrElse(1) { JSUndefined }
                if (!interpreter.isCallable(setter)) {
                    interpreter.typeError("__defineSetter__ requires a callable setter")
                }
                val key = interpreter.toPropertyKey(args.getOrElse(0) { JSUndefined })
                defineAccessorPropertyOrThrow(
                    interpreter, objectValue, key, null, setter,
                    preserveGet = true, preserveSet = false,
                )
                JSUndefined
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        objectPrototype.defineDataProperty(
            key = PropertyKey.Str("__lookupGetter__"),
            value = BuiltinHelpers.method(intrinsics, "__lookupGetter__", 1) { interpreter, thisValue, args ->
                lookupAccessor(interpreter, thisValue, args.getOrElse(0) { JSUndefined }, getter = true)
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        objectPrototype.defineDataProperty(
            key = PropertyKey.Str("__lookupSetter__"),
            value = BuiltinHelpers.method(intrinsics, "__lookupSetter__", 1) { interpreter, thisValue, args ->
                lookupAccessor(interpreter, thisValue, args.getOrElse(0) { JSUndefined }, getter = false)
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        // Object.prototype.constructor
        objectPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = objectConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )
    }

    private fun installStaticMethods(registry: BuiltinRegistry, objectConstructor: JSNativeFunction) {
        val intrinsics = registry.intrinsics
        val objectPrototype = intrinsics.objectPrototype
        val arrayPrototype = intrinsics.arrayPrototype

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("keys"),
            value = BuiltinHelpers.method(intrinsics, "keys", 1) { interpreter, _, args ->
                val objectValue = interpreter.toObject(args.getOrElse(0) { throwObjectError(interpreter) })
                JSArray(
                    enumerableOwnStringKeys(objectValue).map { JSString(it) }.toMutableList(),
                    prototype = arrayPrototype,
                )
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("values"),
            value = BuiltinHelpers.method(intrinsics, "values", 1) { interpreter, _, args ->
                val objectValue = interpreter.toObject(args.getOrElse(0) { throwObjectError(interpreter) })
                val values = ArrayList<JSValue?>()
                for (key in enumerableOwnStringKeys(objectValue)) {
                    values.add(interpreter.getV(objectValue, PropertyKey.Str(key)))
                }
                JSArray(values, prototype = arrayPrototype)
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("entries"),
            value = BuiltinHelpers.method(intrinsics, "entries", 1) { interpreter, _, args ->
                val objectValue = interpreter.toObject(args.getOrElse(0) { throwObjectError(interpreter) })
                val entries = ArrayList<JSValue?>()
                for (key in enumerableOwnStringKeys(objectValue)) {
                    val pair = ArrayList<JSValue?>()
                    pair.add(JSString(key))
                    pair.add(interpreter.getV(objectValue, PropertyKey.Str(key)))
                    entries.add(JSArray(pair, prototype = arrayPrototype))
                }
                JSArray(entries, prototype = arrayPrototype)
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("assign"),
            value = BuiltinHelpers.method(intrinsics, "assign", 2) { interpreter, _, args ->
                val target = interpreter.toObject(args.getOrElse(0) { throwObjectError(interpreter) })
                for (index in 1 until args.size) {
                    val source = args[index]
                    if (source is JSNull || source is JSUndefined) continue
                    val sourceObject = interpreter.toObject(source)
                    // 复制所有可枚举自有属性，包括 Symbol 键（规范要求）
                    for (propertyKey in sourceObject.ownPropertyKeys()) {
                        if (sourceObject.getOwnProperty(propertyKey)?.enumerable != true) continue
                        val value = interpreter.getV(sourceObject, propertyKey)
                        interpreter.putV(target, propertyKey, value, strict = true)
                    }
                }
                target
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("create"),
            value = BuiltinHelpers.method(intrinsics, "create", 2) { interpreter, _, args ->
                val prototypeValue = args.getOrNull(0) ?: JSUndefined
                val prototype = when (prototypeValue) {
                    is JSNull -> null
                    is JSObject -> prototypeValue
                    else -> interpreter.typeError("Object prototype may only be an Object or null")
                }
                val result = JSObject(prototype = prototype)
                val propertiesValue = args.getOrNull(1)
                if (propertiesValue != null && propertiesValue !is JSUndefined) {
                    // 与规范一致：非 undefined 的第二参数先做 ToObject（null 会抛 TypeError）
                    val properties = interpreter.toObject(propertiesValue)
                    applyDescriptors(interpreter, result, properties)
                }
                result
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("getPrototypeOf"),
            value = BuiltinHelpers.method(intrinsics, "getPrototypeOf", 1) { interpreter, _, args ->
                val objectValue = interpreter.toObject(args.getOrElse(0) { throwObjectError(interpreter) })
                objectValue.prototype ?: JSNull
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("setPrototypeOf"),
            value = BuiltinHelpers.method(intrinsics, "setPrototypeOf", 2) { interpreter, _, args ->
                val objectValue = args.getOrElse(0) { JSUndefined }
                val prototypeValue = args.getOrElse(1) { JSUndefined }
                if (objectValue is JSNull || objectValue is JSUndefined) {
                    interpreter.typeError("Object.setPrototypeOf called on null or undefined")
                }
                val newPrototype = when (prototypeValue) {
                    is JSNull -> null
                    is JSObject -> prototypeValue
                    else -> interpreter.typeError("Object prototype may only be an Object or null")
                }
                // 原始值按 ES2015 返回自身（不做包装）
                if (objectValue !is JSObject) return@method objectValue
                // 不可扩展对象只能保持原原型
                if (!objectValue.isExtensible() && objectValue.prototype !== newPrototype) {
                    interpreter.typeError("Cannot set prototype of non-extensible object")
                }
                objectValue.prototype = newPrototype
                objectValue
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("getOwnPropertyDescriptor"),
            value = BuiltinHelpers.method(intrinsics, "getOwnPropertyDescriptor", 2) { interpreter, _, args ->
                val objectValue = interpreter.toObject(args.getOrElse(0) { throwObjectError(interpreter) })
                val key = interpreter.toPropertyKey(args.getOrElse(1) { JSUndefined })
                createDescriptorObject(interpreter, objectValue, key)
                    ?: return@method JSUndefined
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("getOwnPropertyDescriptors"),
            value = BuiltinHelpers.method(intrinsics, "getOwnPropertyDescriptors", 1) { interpreter, _, args ->
                val objectValue = interpreter.toObject(args.getOrElse(0) { throwObjectError(interpreter) })
                val result = JSObject(prototype = objectPrototype)
                for (key in objectValue.ownPropertyKeys()) {
                    val descriptorObject = createDescriptorObject(interpreter, objectValue, key)
                        ?: continue
                    result.defineDataProperty(
                        key = key,
                        value = descriptorObject,
                        writable = true,
                        enumerable = true,
                        configurable = true,
                    )
                }
                result
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("getOwnPropertyNames"),
            value = BuiltinHelpers.method(intrinsics, "getOwnPropertyNames", 1) { interpreter, _, args ->
                val objectValue = interpreter.toObject(args.getOrElse(0) { throwObjectError(interpreter) })
                val names = objectValue.ownPropertyKeys()
                    .filterIsInstance<PropertyKey.Str>()
                    .map { JSString(it.value) }
                JSArray(names.toMutableList(), prototype = arrayPrototype)
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("getOwnPropertySymbols"),
            value = BuiltinHelpers.method(intrinsics, "getOwnPropertySymbols", 1) { interpreter, _, args ->
                val objectValue = interpreter.toObject(args.getOrElse(0) { throwObjectError(interpreter) })
                val symbols = objectValue.ownPropertyKeys()
                    .filterIsInstance<PropertyKey.Sym>()
                    .map { it.symbol }
                JSArray(symbols.toMutableList(), prototype = arrayPrototype)
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("defineProperty"),
            value = BuiltinHelpers.method(intrinsics, "defineProperty", 3) { interpreter, _, args ->
                val objectValue = args.getOrNull(0)
                if (objectValue !is JSObject) {
                    interpreter.typeError("Object.defineProperty called on non-object")
                }
                val key = interpreter.toPropertyKey(args.getOrElse(1) { JSUndefined })
                val descriptorObject = args.getOrNull(2)
                if (descriptorObject !is JSObject) {
                    interpreter.typeError("Property description must be an object")
                }
                definePropertyFromDescriptor(interpreter, objectValue, key, descriptorObject)
                objectValue
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("defineProperties"),
            value = BuiltinHelpers.method(intrinsics, "defineProperties", 2) { interpreter, _, args ->
                val objectValue = args.getOrNull(0)
                if (objectValue !is JSObject) {
                    interpreter.typeError("Object.defineProperties called on non-object")
                }
                val propertiesValue = args.getOrNull(1)
                if (propertiesValue == null || propertiesValue is JSUndefined || propertiesValue is JSNull) {
                    interpreter.typeError("Object.defineProperties requires a properties object")
                }
                // 原始值通过 ToObject 包装；布尔/数字通常没有可枚举自有属性
                val propertiesObject = interpreter.toObject(propertiesValue)
                applyDescriptors(interpreter, objectValue, propertiesObject)
                objectValue
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("is"),
            value = BuiltinHelpers.method(intrinsics, "is", 2) { interpreter, _, args ->
                val left = args.getOrNull(0) ?: JSUndefined
                val right = args.getOrNull(1) ?: JSUndefined
                JSBoolean.getJsBoolean(sameValue(interpreter, left, right))
            },
        )

        // Object.hasOwn(obj, key)：HasOwnProperty(ToObject(obj), ToPropertyKey(key))
        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("hasOwn"),
            value = BuiltinHelpers.method(intrinsics, "hasOwn", 2) { interpreter, _, args ->
                val objectValue = interpreter.toObject(args.getOrElse(0) { JSUndefined })
                val key = interpreter.toPropertyKey(args.getOrElse(1) { JSUndefined })
                JSBoolean.getJsBoolean(objectValue.hasOwnProperty(key))
            },
        )

        // Object.fromEntries(iterable)：把 [key, value] 序列写入新对象
        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("fromEntries"),
            value = BuiltinHelpers.method(intrinsics, "fromEntries", 1) { interpreter, _, args ->
                val target = JSObject(prototype = intrinsics.objectPrototype)
                interpreter.consumeIterator(args.getOrElse(0) { JSUndefined }) { entry ->
                    if (entry !is JSObject) {
                        interpreter.typeError("Iterator value is not an entry object")
                    }
                    val keyValue = interpreter.getV(entry, PropertyKey.Str("0"))
                    val value = interpreter.getV(entry, PropertyKey.Str("1"))
                    val key = interpreter.toPropertyKey(keyValue)
                    val defined = interpreter.defineOwnPropertyOn(
                        target,
                        key,
                        PropertyDescriptor.data(value),
                    )
                    if (!defined) {
                        interpreter.typeError("Cannot create property '$key'")
                    }
                }
                target
            },
        )

        // Object.groupBy(items, callback)：分组对象为 null 原型，组值为普通数组
        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("groupBy"),
            value = BuiltinHelpers.method(intrinsics, "groupBy", 2) { interpreter, _, args ->
                val items = args.getOrElse(0) { JSUndefined }
                val callback = args.getOrElse(1) { JSUndefined }
                if (!interpreter.isCallable(callback)) {
                    interpreter.typeError("Object.groupBy requires a callable callback")
                }
                val result = JSObject(prototype = null)
                val groups = LinkedHashMap<PropertyKey, JSArray>()
                var index = 0.0
                interpreter.consumeIterator(items) { value ->
                    val keyValue = interpreter.callFunction(
                        callback,
                        JSUndefined,
                        listOf(value, JSNumber(index)),
                    )
                    index += 1.0
                    val key = interpreter.toPropertyKey(keyValue)
                    val group = groups[key]
                    if (group == null) {
                        val created = JSArray(prototype = intrinsics.arrayPrototype)
                        created.setElement(0, value)
                        groups[key] = created
                        val defined = interpreter.defineOwnPropertyOn(
                            result,
                            key,
                            PropertyDescriptor.data(created),
                        )
                        if (!defined) {
                            interpreter.typeError("Cannot create group '$key'")
                        }
                    } else {
                        group.setElement(group.lengthLong, value)
                    }
                }
                result
            },
        )

        // 冻结 / 密封 / 防扩展
        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("freeze"),
            value = BuiltinHelpers.method(intrinsics, "freeze", 1) { _, _, args ->
                val value = args.getOrNull(0) ?: JSUndefined
                when (value) {
                    is JSArray -> {
                        value.freezeArray()
                        // 数组的普通自有属性（如 arr.foo）同样需要冻结
                        for (key in value.ownPropertyKeys()) {
                            if (key == PropertyKey.LENGTH) continue
                            val descriptor = value.getOwnProperty(key) ?: continue
                            descriptor.configurable = false
                            if (descriptor.isData) descriptor.writable = false
                        }
                    }

                    is JSObject -> {
                        for (key in value.ownPropertyKeys()) {
                            val descriptor = value.getOwnProperty(key) ?: continue
                            descriptor.configurable = false
                            if (descriptor.isData) descriptor.writable = false
                        }
                        value.preventExtensions()
                    }

                    else -> Unit
                }
                value
            },
        )
        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("seal"),
            value = BuiltinHelpers.method(intrinsics, "seal", 1) { _, _, args ->
                val value = args.getOrNull(0) ?: JSUndefined
                when (value) {
                    is JSArray -> {
                        value.sealArray()
                        for (key in value.ownPropertyKeys()) {
                            if (key == PropertyKey.LENGTH) continue
                            value.getOwnProperty(key)?.configurable = false
                        }
                    }

                    is JSObject -> {
                        for (key in value.ownPropertyKeys()) {
                            value.getOwnProperty(key)?.configurable = false
                        }
                        value.preventExtensions()
                    }

                    else -> Unit
                }
                value
            },
        )
        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("preventExtensions"),
            value = BuiltinHelpers.method(intrinsics, "preventExtensions", 1) { _, _, args ->
                val value = args.getOrNull(0) ?: JSUndefined
                if (value is JSObject) value.preventExtensions()
                value
            },
        )
        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("isExtensible"),
            value = BuiltinHelpers.method(intrinsics, "isExtensible", 1) { _, _, args ->
                val value = args.getOrNull(0)
                JSBoolean.getJsBoolean(value is JSObject && value.isExtensible())
            },
        )
        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("isFrozen"),
            value = BuiltinHelpers.method(intrinsics, "isFrozen", 1) { _, _, args ->
                val value = args.getOrNull(0)
                JSBoolean.getJsBoolean(
                    when (value) {
                        is JSArray -> value.frozen
                        is JSObject -> !value.isExtensible() && value.ownPropertyKeys().all { key ->
                            val descriptor = value.getOwnProperty(key)
                            descriptor == null ||
                                (!descriptor.configurable && (descriptor.isAccessor || !descriptor.writable))
                        }

                        else -> true // 原始值按规范被视为已冻结
                    }
                )
            },
        )
        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("isSealed"),
            value = BuiltinHelpers.method(intrinsics, "isSealed", 1) { _, _, args ->
                val value = args.getOrNull(0)
                JSBoolean.getJsBoolean(
                    when (value) {
                        is JSArray -> value.sealed
                        is JSObject -> !value.isExtensible() && value.ownPropertyKeys().all { key ->
                            value.getOwnProperty(key)?.configurable == false
                        }

                        else -> true
                    }
                )
            },
        )
    }

    /**
     * 从描述符对象读取属性描述符并定义到目标对象。
     *
     * 与规范一致：
     * - 描述符对象通过 [[Get]] 读取（包括继承字段）；
     * - 未提供的字段在已有属性上保留当前值；
     * - 检查不可配置属性的不变量。
     */
    private suspend fun definePropertyFromDescriptor(
        interpreter: com.muedsa.js.runtime.Interpreter,
        objectValue: JSObject,
        key: PropertyKey,
        descriptorObject: JSObject,
    ) {
        suspend fun has(name: String): Boolean =
            descriptorObject.hasProperty(PropertyKey.Str(name))

        suspend fun read(name: String): JSValue =
            interpreter.getV(descriptorObject, PropertyKey.Str(name))

        val hasValue = has("value")
        val hasWritable = has("writable")
        val hasGet = has("get")
        val hasSet = has("set")
        val hasEnumerable = has("enumerable")
        val hasConfigurable = has("configurable")

        val value = if (hasValue) read("value") else null
        val writable = if (hasWritable) interpreter.toBoolean(read("writable")) else null
        val getter = if (hasGet) read("get") else null
        val setter = if (hasSet) read("set") else null
        val enumerable = if (hasEnumerable) interpreter.toBoolean(read("enumerable")) else null
        val configurable = if (hasConfigurable) interpreter.toBoolean(read("configurable")) else null

        if (hasGet && getter != null && getter !is JSUndefined && !interpreter.isCallable(getter)) {
            interpreter.typeError("Getter must be a function")
        }
        if (hasSet && setter != null && setter !is JSUndefined && !interpreter.isCallable(setter)) {
            interpreter.typeError("Setter must be a function")
        }
        val isAccessorDescriptor = hasGet || hasSet
        val isDataDescriptor = hasValue || hasWritable
        if (isAccessorDescriptor && isDataDescriptor) {
            interpreter.typeError("Invalid property descriptor: cannot be both accessor and data")
        }
        var current = objectValue.getOwnProperty(key)
        // ES5 10.6：arguments 的映射索引在描述符层面视为可写数据属性，
        // 否则从映射访问器合并会错误保留参数 setter
        if (objectValue is JSArguments && current != null) {
            val mapping = objectValue.parameterMapping(key)
            if (mapping != null) {
                current = PropertyDescriptor.data(
                    value = mapping.read(),
                    writable = true,
                    enumerable = current.enumerable,
                    configurable = true,
                )
            }
        }

        if (current != null && !current.configurable) {
            if (hasConfigurable && configurable == true) {
                interpreter.typeError("Cannot redefine property: $key")
            }
            if (hasEnumerable && enumerable != current.enumerable) {
                interpreter.typeError("Cannot redefine property: $key")
            }
            if (isAccessorDescriptor && current.isData) {
                interpreter.typeError("Cannot redefine property: $key")
            }
            if (isDataDescriptor && current.isAccessor) {
                interpreter.typeError("Cannot redefine property: $key")
            }
            if (current.isData) {
                if (!current.writable) {
                    if (hasWritable && writable == true) {
                        interpreter.typeError("Cannot redefine property: $key")
                    }
                    if (hasValue && !sameDescriptorValue(
                            interpreter,
                            value ?: JSUndefined,
                            current.value ?: JSUndefined,
                        )
                    ) {
                        interpreter.typeError("Cannot redefine property: $key")
                    }
                }
            } else {
                if (hasGet && !sameDescriptorValue(
                        interpreter,
                        getter ?: JSUndefined,
                        current.get ?: JSUndefined,
                    )
                ) {
                    interpreter.typeError("Cannot redefine property: $key")
                }
                if (hasSet && !sameDescriptorValue(
                        interpreter,
                        setter ?: JSUndefined,
                        current.set ?: JSUndefined,
                    )
                ) {
                    interpreter.typeError("Cannot redefine property: $key")
                }
            }
        }

        val descriptor = PropertyDescriptor()
        // 访问器类型必须显式记录：get/set 可以都为 undefined
        descriptor.accessor = isAccessorDescriptor || (!isDataDescriptor && current?.isAccessor == true)
        descriptor.enumerable = enumerable ?: current?.enumerable ?: false
        descriptor.configurable = configurable ?: current?.configurable ?: false

        when {
            current == null -> {
                if (isAccessorDescriptor) {
                    descriptor.get = if (hasGet && getter !is JSUndefined) getter else null
                    descriptor.set = if (hasSet && setter !is JSUndefined) setter else null
                } else {
                    descriptor.value = value ?: JSUndefined
                    descriptor.writable = writable ?: false
                }
            }

            isAccessorDescriptor -> {
                if (current.isData) {
                    descriptor.get = if (hasGet && getter !is JSUndefined) getter else null
                    descriptor.set = if (hasSet && setter !is JSUndefined) setter else null
                } else {
                    descriptor.get = if (hasGet) {
                        if (getter is JSUndefined) null else getter
                    } else {
                        current.get
                    }
                    descriptor.set = if (hasSet) {
                        if (setter is JSUndefined) null else setter
                    } else {
                        current.set
                    }
                }
            }

            isDataDescriptor -> {
                if (current.isAccessor) {
                    descriptor.value = value ?: JSUndefined
                    descriptor.writable = writable ?: false
                } else {
                    descriptor.value = if (hasValue) value else current.value
                    descriptor.writable = if (hasWritable) writable ?: false else current.writable
                }
            }

            else -> {
                // 通用描述符只修改 enumerable/configurable
                descriptor.value = current.value
                descriptor.writable = current.writable
                descriptor.get = current.get
                descriptor.set = current.set
            }
        }

        if (!interpreter.defineOwnPropertyOn(objectValue, key, descriptor)) {
            interpreter.typeError("Cannot define property '${key}'")
        }
    }

    /** Annex B：定义访问器并保留未指定的另一侧访问器。 */
    private suspend fun defineAccessorPropertyOrThrow(
        interpreter: Interpreter,
        objectValue: JSObject,
        key: PropertyKey,
        getter: JSValue?,
        setter: JSValue?,
        preserveGet: Boolean,
        preserveSet: Boolean,
    ) {
        val existing = objectValue.getOwnProperty(key)
        // 描述符固定带 configurable: true；不可配置属性无法重新定义
        if (existing != null && !existing.configurable) {
            interpreter.typeError("Cannot redefine property: ${key}")
        }
        val finalGet = if (preserveGet && existing != null && existing.isAccessor) {
            existing.get
        } else {
            getter
        }
        val finalSet = if (preserveSet && existing != null && existing.isAccessor) {
            existing.set
        } else {
            setter
        }
        val defined = interpreter.defineOwnPropertyOn(
            objectValue,
            key,
            PropertyDescriptor.accessor(
                finalGet,
                finalSet,
                enumerable = true,
                configurable = true,
            ),
        )
        if (!defined) {
            interpreter.typeError("Cannot define property '${key}'")
        }
    }

    /** __lookupGetter__/__lookupSetter__：沿原型链查找访问器。 */
    private suspend fun lookupAccessor(
        interpreter: Interpreter,
        thisValue: JSValue,
        keyValue: JSValue,
        getter: Boolean,
    ): JSValue {
        val objectValue = interpreter.toObject(thisValue)
        val key = interpreter.toPropertyKey(keyValue)
        var current: JSObject? = objectValue
        while (current != null) {
            val descriptor = current.getOwnProperty(key)
            if (descriptor != null) {
                if (!descriptor.isAccessor) return JSUndefined
                return (if (getter) descriptor.get else descriptor.set) ?: JSUndefined
            }
            current = current.prototype
        }
        return JSUndefined
    }

    /** 描述符不变量比较使用 SameValue（区分 -0，NaN 视为相等）。 */
    private fun sameDescriptorValue(
        interpreter: com.muedsa.js.runtime.Interpreter,
        left: JSValue,
        right: JSValue,
    ): Boolean {
        if (left is JSNumber && right is JSNumber) {
            if (left.value.isNaN() && right.value.isNaN()) return true
            if (left.value == 0.0 && right.value == 0.0) {
                return 1.0 / left.value == 1.0 / right.value
            }
        }
        return interpreter.strictEquals(left, right)
    }

    /** 批量应用描述符对象（Object.create / Object.defineProperties）。 */
    private suspend fun applyDescriptors(
        interpreter: com.muedsa.js.runtime.Interpreter,
        target: JSObject,
        properties: JSObject,
    ) {
        for (key in properties.ownPropertyKeys()) {
            // Object.defineProperties/Object.create 只处理可枚举的自有描述符条目
            if (properties.getOwnProperty(key)?.enumerable != true) continue
            val descriptorValue = interpreter.getV(properties, key)
            val descriptorObject = descriptorValue as? JSObject
                ?: interpreter.typeError("Property description must be an object")
            definePropertyFromDescriptor(interpreter, target, key, descriptorObject)
        }
    }

    /** 获取可枚举的自有字符串键。 */
    private fun enumerableOwnStringKeys(objectValue: JSObject): List<String> {
        val keys = mutableListOf<String>()
        for (key in objectValue.ownPropertyKeys()) {
            if (key !is PropertyKey.Str) continue
            if (objectValue.getOwnProperty(key)?.enumerable == true) {
                keys.add(key.value)
            }
        }
        return keys
    }

    /** Object.prototype.toString 的类型标签。 */
    /** 构造属性描述符对象（getOwnPropertyDescriptor(s) 共用）。 */
    private suspend fun createDescriptorObject(
        interpreter: Interpreter,
        objectValue: JSObject,
        key: PropertyKey,
    ): JSObject? {
        var descriptor = objectValue.getOwnProperty(key) ?: return null
        if (objectValue is JSArguments && descriptor.isAccessor) {
            val mapping = objectValue.parameterMapping(key)
            if (mapping != null) {
                // ES5 10.6：映射索引在描述符层面是数据属性
                descriptor = PropertyDescriptor.data(
                    value = mapping.read(),
                    writable = true,
                    enumerable = descriptor.enumerable,
                    configurable = true,
                )
            }
        }
        val result = JSObject(prototype = interpreter.intrinsics.objectPrototype)
        // 规范：描述符对象的属性通过 CreateDataProperty 创建，全部可枚举
        if (descriptor.isAccessor) {
            result.defineDataProperty(
                PropertyKey.Str("get"), descriptor.get ?: JSUndefined,
                writable = true, enumerable = true, configurable = true,
            )
            result.defineDataProperty(
                PropertyKey.Str("set"), descriptor.set ?: JSUndefined,
                writable = true, enumerable = true, configurable = true,
            )
        } else {
            result.defineDataProperty(
                PropertyKey.Str("value"), descriptor.value ?: JSUndefined,
                writable = true, enumerable = true, configurable = true,
            )
            result.defineDataProperty(
                PropertyKey.Str("writable"),
                JSBoolean.getJsBoolean(descriptor.writable),
                writable = true, enumerable = true, configurable = true,
            )
        }
        result.defineDataProperty(
            PropertyKey.Str("enumerable"),
            JSBoolean.getJsBoolean(descriptor.enumerable),
            writable = true, enumerable = true, configurable = true,
        )
        result.defineDataProperty(
            PropertyKey.Str("configurable"),
            JSBoolean.getJsBoolean(descriptor.configurable),
            writable = true, enumerable = true, configurable = true,
        )
        return result
    }

    private fun objectTag(intrinsics: com.muedsa.js.runtime.Intrinsics, value: JSValue): String = when (value) {
        is JSUndefined -> "Undefined"
        is JSNull -> "Null"
        is JSBoolean -> "Boolean"
        is JSNumber -> "Number"
        is JSString -> "String"
        is JSSymbol -> "Object"
        is JSArray -> "Array"
        is JSFunction, is JSNativeFunction -> "Function"
        is JSRegExp -> "RegExp"
        is com.muedsa.js.runtime.value.JSDate -> "Date"
        is JSError -> "Error"
        is JSArguments -> "Arguments"
        is JSBoxedPrimitive -> when (value.primitive) {
            is JSNumber -> "Number"
            is JSString -> "String"
            is JSBoolean -> "Boolean"
            else -> "Object"
        }
        is JSObject -> when (value.prototype) {
            intrinsics.stringPrototype -> "String"
            intrinsics.numberPrototype -> "Number"
            intrinsics.booleanPrototype -> "Boolean"
            else -> when (value) {
                // 原型对象本身也是对应的内建包装对象（ES5 15.7.4/15.5.4）
                intrinsics.stringPrototype -> "String"
                intrinsics.numberPrototype -> "Number"
                intrinsics.booleanPrototype -> "Boolean"
                else -> "Object"
            }
        }
    }

    private fun sameValue(interpreter: com.muedsa.js.runtime.Interpreter, left: JSValue, right: JSValue): Boolean {
        if (left is JSNumber && right is JSNumber) {
            if (left.value.isNaN() && right.value.isNaN()) return true
            if (left.value == 0.0 && right.value == 0.0) {
                return 1.0 / left.value == 1.0 / right.value
            }
            return left.value == right.value
        }
        return interpreter.strictEquals(left, right)
    }

    private fun throwObjectError(interpreter: com.muedsa.js.runtime.Interpreter): Nothing =
        interpreter.typeError("Cannot convert undefined or null to object")
}
