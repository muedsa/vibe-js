package com.muedsa.js.runtime.value

/**
 * undefined 原始值（单例）。
 */
object JSUndefined : JSValue() {
    override val isPrimitive: Boolean = true
    override fun toPrimitiveBoolean(): Boolean = false
    override fun toPrimitiveNumber(): Double = Double.NaN
    override fun toPrimitiveString(): String = "undefined"
}
