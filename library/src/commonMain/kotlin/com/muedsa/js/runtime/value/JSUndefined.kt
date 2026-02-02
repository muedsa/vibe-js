package com.muedsa.js.runtime.value

object JSUndefined : JSValue() {
    override val isPrimitive: Boolean = true
    override fun toPrimitiveBoolean() = false
    override fun toPrimitiveNumber() = Double.NaN
    override fun toPrimitiveString() = "undefined"
}
