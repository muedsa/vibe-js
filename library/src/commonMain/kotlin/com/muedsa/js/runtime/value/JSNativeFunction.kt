package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.Interpreter

class JSNativeFunction(
    val name: String,
    lazyProperties: Lazy<MutableMap<String, JSValue>> = lazy { mutableMapOf() },
    prototype: JSValue = NativeFunctionPrototype,
    val function: (interpreter: Interpreter, thisValue: JSValue, args: List<JSValue>) -> JSValue = { _, _, _ -> JSUndefined },
) : JSObject(
    lazyProperties = lazyProperties,
    prototype = prototype,
) {
    override fun toPrimitiveBoolean() = true
    override fun toPrimitiveNumber() = Double.NaN
    override fun toPrimitiveString() = "function ${name}() { [native code] }"
}

val NativeFunctionPrototype = JSNativeFunction(
    name = "",
    lazyProperties = lazy {
        mutableMapOf(
            "[Symbol.toStringTag]" to JSString("Function")
        )
    },
    prototype = JSNull,
) { _, _, _ -> JSUndefined }
