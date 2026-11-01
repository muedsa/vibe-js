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
        private const val SMALL_INT_MIN = -128
        private const val SMALL_INT_MAX = 1024

        /** 小整数缓存：循环计数、下标与常见字面量无需重复分配包装对象。 */
        private val SMALL_INTS = Array(SMALL_INT_MAX - SMALL_INT_MIN + 1) {
            JSNumber((it + SMALL_INT_MIN).toDouble())
        }

        val ZERO = JSNumber(0.0)
        val NEGATIVE_ZERO = JSNumber(-0.0)
        val ONE = JSNumber(1.0)
        val NAN = JSNumber(Double.NaN)
        val POSITIVE_INFINITY = JSNumber(Double.POSITIVE_INFINITY)
        val NEGATIVE_INFINITY = JSNumber(Double.NEGATIVE_INFINITY)
        val MAX_VALUE = JSNumber(Double.MAX_VALUE)
        val MIN_VALUE = JSNumber(Double.MIN_VALUE)

        /**
         * 获取 Number 原始值包装：复用特殊值与 [-128, 1024] 内的小整数实例。
         *
         * 注意 ±0 必须通过原始位区分，避免把 -0 折叠为 +0（Object.is 可观察）。
         */
        fun of(value: Double): JSNumber {
            val bits = value.toRawBits()
            if (bits == 0L) return ZERO
            if (bits == Long.MIN_VALUE) return NEGATIVE_ZERO
            if (value.isNaN()) return NAN
            if (value == Double.POSITIVE_INFINITY) return POSITIVE_INFINITY
            if (value == Double.NEGATIVE_INFINITY) return NEGATIVE_INFINITY
            if (value >= SMALL_INT_MIN && value <= SMALL_INT_MAX) {
                val intValue = value.toInt()
                if (intValue.toDouble() == value) return SMALL_INTS[intValue - SMALL_INT_MIN]
            }
            return JSNumber(value)
        }
    }
}
