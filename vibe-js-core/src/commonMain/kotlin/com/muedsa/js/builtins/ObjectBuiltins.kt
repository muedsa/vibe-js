package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
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
            if (value is JSObject) value else interpreter.toObject(value)
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
                val objectValue = interpreter.toObject(thisValue)
                val key = interpreter.toPropertyKey(args.getOrElse(0) { JSUndefined })
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
                val objectValue = interpreter.toObject(thisValue)
                val key = interpreter.toPropertyKey(args.getOrElse(0) { JSUndefined })
                JSBoolean.getJsBoolean(objectValue.getOwnProperty(key)?.enumerable == true)
            },
        )

        objectPrototype.defineDataProperty(
            key = PropertyKey.Str("valueOf"),
            value = BuiltinHelpers.method(intrinsics, "valueOf", 0) { _, thisValue, _ ->
                thisValue
            },
        )

        objectPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            value = BuiltinHelpers.method(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
                val tagValue = if (thisValue is JSObject) {
                    interpreter.getV(thisValue, PropertyKey.Sym(intrinsics.symbolToStringTag))
                } else {
                    JSUndefined
                }
                val tag = if (tagValue is JSString) {
                    tagValue.value
                } else {
                    objectTag(intrinsics, thisValue)
                }
                JSString("[object $tag]")
            },
        )

        objectPrototype.getOwnDataValue(PropertyKey.Str("toString"))?.let { toStringFunction ->
            objectPrototype.defineDataProperty(
                key = PropertyKey.Str("toLocaleString"),
                value = toStringFunction,
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }

        // __proto__ 访问器（常见写法；规范中位于 Object.prototype）
        objectPrototype.defineAccessorProperty(
            key = PropertyKey.Str("__proto__"),
            get = BuiltinHelpers.method(intrinsics, "get __proto__", 0) { _, thisValue, _ ->
                (thisValue as? JSObject)?.prototype ?: JSNull
            },
            set = BuiltinHelpers.method(intrinsics, "set __proto__", 1) { _, thisValue, args ->
                val target = thisValue
                val newPrototype = args.getOrNull(0)
                if (target is JSObject && (newPrototype is JSObject || newPrototype is JSNull)) {
                    target.prototype = newPrototype as? JSObject
                }
                JSUndefined
            },
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
                if (propertiesValue != null && propertiesValue !is JSUndefined && propertiesValue !is JSNull) {
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
                val objectValue = args.getOrNull(0)
                val prototypeValue = args.getOrNull(1)
                if (objectValue !is JSObject) {
                    interpreter.typeError("Object.setPrototypeOf called on non-object")
                }
                when (prototypeValue) {
                    is JSNull -> objectValue.prototype = null
                    is JSObject -> objectValue.prototype = prototypeValue
                    else -> interpreter.typeError("Object prototype may only be an Object or null")
                }
                objectValue
            },
        )

        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("getOwnPropertyDescriptor"),
            value = BuiltinHelpers.method(intrinsics, "getOwnPropertyDescriptor", 2) { interpreter, _, args ->
                val objectValue = interpreter.toObject(args.getOrElse(0) { throwObjectError(interpreter) })
                val key = interpreter.toPropertyKey(args.getOrElse(1) { JSUndefined })
                val descriptor = objectValue.getOwnProperty(key)
                    ?: return@method JSUndefined
                val result = JSObject(prototype = objectPrototype)
                if (descriptor.isAccessor) {
                    result.defineDataProperty(PropertyKey.Str("get"), descriptor.get ?: JSUndefined)
                    result.defineDataProperty(PropertyKey.Str("set"), descriptor.set ?: JSUndefined)
                } else {
                    result.defineDataProperty(PropertyKey.Str("value"), descriptor.value ?: JSUndefined)
                    result.defineDataProperty(
                        PropertyKey.Str("writable"),
                        JSBoolean.getJsBoolean(descriptor.writable),
                    )
                }
                result.defineDataProperty(
                    PropertyKey.Str("enumerable"),
                    JSBoolean.getJsBoolean(descriptor.enumerable),
                )
                result.defineDataProperty(
                    PropertyKey.Str("configurable"),
                    JSBoolean.getJsBoolean(descriptor.configurable),
                )
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
                val propertiesObject = args.getOrNull(1)
                if (propertiesObject !is JSObject) {
                    interpreter.typeError("Object.defineProperties requires a properties object")
                }
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

        // 冻结 / 密封 / 防扩展
        objectConstructor.defineDataProperty(
            key = PropertyKey.Str("freeze"),
            value = BuiltinHelpers.method(intrinsics, "freeze", 1) { _, _, args ->
                val value = args.getOrNull(0) ?: JSUndefined
                when (value) {
                    is JSArray -> value.freezeArray()
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
                    is JSArray -> value.sealArray()
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

    /** 从描述符对象读取属性描述符并定义到目标对象。 */
    private suspend fun definePropertyFromDescriptor(
        interpreter: com.muedsa.js.runtime.Interpreter,
        objectValue: JSObject,
        key: PropertyKey,
        descriptorObject: JSObject,
    ) {
        val descriptor = PropertyDescriptor()
        val has = { name: String -> descriptorObject.hasProperty(PropertyKey.Str(name)) }
        if (has("value")) descriptor.value = interpreter.getV(descriptorObject, PropertyKey.Str("value"))
        if (has("get")) {
            val getter = interpreter.getV(descriptorObject, PropertyKey.Str("get"))
            descriptor.get = if (getter is JSUndefined) null else getter
        }
        if (has("set")) {
            val setter = interpreter.getV(descriptorObject, PropertyKey.Str("set"))
            descriptor.set = if (setter is JSUndefined) null else setter
        }
        descriptor.writable = has("writable") &&
            interpreter.toBoolean(interpreter.getV(descriptorObject, PropertyKey.Str("writable")))
        descriptor.enumerable = has("enumerable") &&
            interpreter.toBoolean(interpreter.getV(descriptorObject, PropertyKey.Str("enumerable")))
        descriptor.configurable = has("configurable") &&
            interpreter.toBoolean(interpreter.getV(descriptorObject, PropertyKey.Str("configurable")))
        if (!objectValue.defineOwnProperty(key, descriptor)) {
            interpreter.typeError("Cannot define property '${key}'")
        }
    }

    /** 批量应用描述符对象（Object.create / Object.defineProperties）。 */
    private suspend fun applyDescriptors(
        interpreter: com.muedsa.js.runtime.Interpreter,
        target: JSObject,
        properties: JSObject,
    ) {
        for (key in properties.ownPropertyKeys()) {
            if (key !is PropertyKey.Str) continue
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
    private fun objectTag(intrinsics: com.muedsa.js.runtime.Intrinsics, value: JSValue): String = when (value) {
        is JSUndefined -> "Undefined"
        is JSNull -> "Null"
        is JSBoolean -> "Boolean"
        is JSNumber -> "Number"
        is JSString -> "String"
        is JSSymbol -> "Symbol"
        is JSArray -> "Array"
        is JSFunction, is JSNativeFunction -> "Function"
        is JSError -> "Error"
        is JSObject -> when (value.prototype) {
            intrinsics.stringPrototype -> "String"
            intrinsics.numberPrototype -> "Number"
            intrinsics.booleanPrototype -> "Boolean"
            intrinsics.symbolPrototype -> "Symbol"
            else -> "Object"
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
