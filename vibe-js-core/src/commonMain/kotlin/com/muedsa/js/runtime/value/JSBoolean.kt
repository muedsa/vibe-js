package com.muedsa.js.runtime.value

/**
 * 布尔原始值。
 *
 * 使用单例表示 true/false，避免重复分配。
 */
sealed class JSBoolean(val value: Boolean) : JSValue() {

    override val isPrimitive: Boolean = true

    override fun toPrimitiveBoolean(): Boolean = value

    override fun toPrimitiveNumber(): Double = if (value) 1.0 else 0.0

    override fun toPrimitiveString(): String = value.toString()

    object TRUE : JSBoolean(true)
    object FALSE : JSBoolean(false)

    companion object {
        /** 根据 Kotlin 布尔值返回对应单例。 */
        fun getJsBoolean(boolean: Boolean): JSBoolean = if (boolean) TRUE else FALSE
    }
}
