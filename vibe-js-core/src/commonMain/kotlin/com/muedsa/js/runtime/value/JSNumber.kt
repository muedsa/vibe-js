package com.muedsa.js.runtime.value

/**
 * 数字原始值（对应 ECMAScript 的 Number 类型，统一使用 Double 存储）。
 */
class JSNumber(val value: Double) : JSValue() {

    override val isPrimitive: Boolean = true

    override fun toPrimitiveBoolean(): Boolean = value != 0.0 && !value.isNaN()

    override fun toPrimitiveNumber(): Double = value

    override fun toPrimitiveString(): String = NumberFormatting.toString(value)

    companion object {
        val ZERO = JSNumber(0.0)
        val ONE = JSNumber(1.0)
        val NAN = JSNumber(Double.NaN)
        val POSITIVE_INFINITY = JSNumber(Double.POSITIVE_INFINITY)
        val NEGATIVE_INFINITY = JSNumber(Double.NEGATIVE_INFINITY)
        val MAX_VALUE = JSNumber(Double.MAX_VALUE)
        val MIN_VALUE = JSNumber(Double.MIN_VALUE)
    }
}
