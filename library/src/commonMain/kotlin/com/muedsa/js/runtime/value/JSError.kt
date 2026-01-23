package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.exception.JSException

data class JSError(
    val name: String,
    val message: String,
) : JSObject(
    properties = mutableMapOf(
        "name" to JSString(name),
        "message" to JSString(message),

    ),
    prototype = ErrorPrototype,
) {
    override fun toPrimitiveString() = "$name: $message"
}

val ErrorPrototype = JSNativeFunction(
    name = "",
    lazyProperties = lazy {
        mutableMapOf(
            "toString" to JSNativeFunction("toString") { interpreter, thisValue, _ ->
                val thisObject = thisValue as? JSObject
                if (thisObject == null || thisObject.isPrimitive) {
                    throw JSException(JSError("TypeError", "Method Error.prototype.toString called on incompatible receiver $thisValue"))
                }
                val name = if(thisObject.hasProperty("name")) {
                    interpreter.getPrimitiveString(thisObject.getProperty("name"))
                } else ""
                val message = if(thisObject.hasProperty("message")) {
                    interpreter.getPrimitiveString(thisObject.getProperty("message"))
                } else ""
                if (name.isNotEmpty() && message.isNotEmpty()) {
                    JSString("$name: $message")
                } else if(name.isNotEmpty()) {
                    JSString(name)
                } else {
                    JSString(message)
                }
            },
            "[Symbol.toStringTag]" to JSString("Error")
        )
    },
    prototype = JSNull,
)

val ErrorConstructor = JSNativeFunction(name = "Error") { interpreter, _, args ->
    val msg = interpreter.getPrimitiveString(args.getOrElse(0) { JSString.EmptyString })
    JSError(name = "Error", message = msg)
}