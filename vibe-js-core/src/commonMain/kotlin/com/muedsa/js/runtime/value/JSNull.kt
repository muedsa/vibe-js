package com.muedsa.js.runtime.value

/**
 * null 原始值（单例）。
 */
object JSNull : JSValue() {
    override val isPrimitive: Boolean = true
    override fun toPrimitiveBoolean(): Boolean = false
    override fun toPrimitiveNumber(): Double = 0.0
    override fun toPrimitiveString(): String = "null"
}
