package com.muedsa.js.runtime.value

/**
 * 属性键。
 *
 * ECMAScript 的属性键可以是字符串或 Symbol。阶段 A 只用到字符串键，
 * 但先把类型设计为 sealed，后续接入 Symbol 时不需要改动 JSObject 的存储结构。
 */
sealed class PropertyKey {

    /** 字符串键。 */
    data class Str(val value: String) : PropertyKey()

    /** Symbol 键（同一 Symbol 的引用相等）。 */
    data class Sym(val symbol: JSSymbol) : PropertyKey()

    override fun toString(): String = when (this) {
        is Str -> value
        is Sym -> symbol.description ?: "Symbol()"
    }

    companion object {
        /** 根据字符串创建属性键。 */
        fun of(value: String): PropertyKey = Str(value)

        val LENGTH = Str("length")
        val CONSTRUCTOR = Str("constructor")
        val PROTOTYPE = Str("prototype")
        val NAME = Str("name")
        val MESSAGE = Str("message")
    }
}
