package com.muedsa.js.runtime.value

object JSNull : JSValue() {
    override val isPrimitive: Boolean = true
    override fun toPrimitiveBoolean() = false
    override fun toPrimitiveNumber() = 0.0
    override fun toPrimitiveString() = "null"
}