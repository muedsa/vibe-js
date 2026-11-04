package com.muedsa.js.runtime.value

/**
 * 属性键。
 *
 * ECMAScript 的属性键可以是字符串或 Symbol；两者都由 [Str] / [Sym] 表示，
 * 存储层不需要为接入 Symbol 改动结构。
 */
sealed class PropertyKey {

    /** 字符串键。 */
    data class Str(val value: String) : PropertyKey() {
        override fun toString(): String = value
    }

    /** Symbol 键（同一 Symbol 的引用相等）。 */
    data class Sym(val symbol: JSSymbol) : PropertyKey() {
        override fun toString(): String = symbol.description ?: "Symbol()"
    }

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
