package com.muedsa.js.runtime.value

sealed class JSValue {
    abstract val isPrimitive: Boolean
    abstract fun toPrimitiveBoolean(): Boolean
    abstract fun toPrimitiveNumber(): Double
    abstract fun toPrimitiveString(): String
}