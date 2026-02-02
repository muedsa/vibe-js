package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.exception.JSException

open class JSObject(
    lazyProperties: Lazy<MutableMap<String, JSValue>> = lazy { mutableMapOf() },
    private val prototype: JSValue = ObjectPrototype,
) : JSValue() {

    constructor(properties: MutableMap<String, JSValue>, prototype: JSValue = ObjectPrototype) : this(
        lazyProperties = lazy { properties },
        prototype = prototype,
    )

    override val isPrimitive: Boolean = false
    override fun toPrimitiveBoolean() = true
    override fun toPrimitiveNumber() = Double.NaN
    override fun toPrimitiveString(): String = "[object Object]"

    private val properties: MutableMap<String, JSValue> by lazyProperties

    fun getOwnProperties(): Map<String, JSValue> {
        return properties
    }

    fun hasProperty(property: String) = properties.containsKey(property)

    open fun getProperty(key: String): JSValue {
        return properties[key] ?: (if (prototype is JSObject) prototype.getProperty(key) else JSUndefined)
    }

    open fun setProperty(key: String, value: JSValue): JSValue {
        properties[key] = value
        return value
    }

//    operator fun get(key: String) = getProperty(key)
//
//    operator fun set(key: String, value: JSValue): JSValue = setProperty(key, value)
}

val ObjectPrototype = JSNativeFunction(
    name = "",
    lazyProperties = lazy {mutableMapOf(
        "hasOwnProperty" to JSNativeFunction("hasOwnProperty") { _, thisValue, args ->
            val thisObject = convertJSValueToJSObject(thisValue)
            val key = args.getOrElse(0) { JSUndefined }.toPrimitiveString()
            JSBoolean.getJsBoolean (thisObject.hasProperty(key))
        },
        "valueOf" to JSNativeFunction("valueOf") { _, thisValue, _ ->
            thisValue
        },
        "toString" to JSNativeFunction("toString") { interpreter, thisValue, _ ->
            val tag = if (thisValue is JSObject) {
                interpreter.getPrimitiveString(thisValue.getProperty("[Symbol.toStringTag]"))
            } else {
                thisValue.toPrimitiveString().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            JSString("[object ${tag}]")
        },
        "[Symbol.toStringTag]" to JSString("Object")
    )},
)

val ObjectConstructor = JSNativeFunction(
    name = "Object",
) { _, _, args -> if (args.isEmpty() || args[0] !is JSObject) JSObject() else args[0] }

fun convertJSValueToJSObject(value: JSValue): JSObject {
    return value as? JSObject
        ?: throw JSException(JSError("TypeError", "Cannot convert undefined or null to object"))
}
