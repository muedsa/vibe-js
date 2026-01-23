package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.exception.JSException

sealed class JSBoolean(
    val value: Boolean,
) : JSObject(
    prototype = BooleanPrototype,
) {
    object True : JSBoolean(true)
    object False : JSBoolean(false)

    override val isPrimitive: Boolean = true
    override fun toPrimitiveBoolean() = value
    override fun toPrimitiveNumber() = if (value) 1.0 else 0.0
    override fun toPrimitiveString() = value.toString()

    companion object {
        fun getJsBoolean(boolean: Boolean) = if (boolean) True else False
    }
}

val BooleanPrototype = JSNativeFunction(
    name = "",
    lazyProperties = lazy {
        mutableMapOf(
            "toString" to JSNativeFunction("Boolean.prototype.toString") { interpreter, thisValue, _ ->
                val thisBoolean = thisValue as? JSBoolean
                    ?: throw JSException(
                        JSError(
                            "TypeError",
                            "Boolean.prototype.toString requires that 'this' be a Boolean"
                        )
                    )
                JSString(interpreter.getPrimitiveString(thisBoolean))
            },
            "[Symbol.toStringTag]" to JSString("Boolean")
        )
    }
)

val BooleanConstructor = JSNativeFunction(
    name = "Boolean",
    lazyProperties = lazy { mutableMapOf() },
) { interpreter, _, args ->
    JSBoolean.getJsBoolean(interpreter.getPrimitiveBoolean(args.getOrElse(0) { JSUndefined }))
}