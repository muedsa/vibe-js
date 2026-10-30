package com.muedsa.js.runtime.value

/**
 * 正则表达式对象。
 *
 * 使用 Kotlin 的 Regex 作为匹配引擎，保留 JS 的 source/flags 与 lastIndex 语义。
 * 说明：Kotlin Regex 与 JS RegExp 在命名组等少数语法上存在差异，阶段 B 覆盖常用 ES5 行为。
 */
class JSRegExp(
    val pattern: String,
    val flags: String,
    val regex: Regex,
    prototype: JSObject?,
) : JSObject(prototype) {

    val global: Boolean = flags.contains('g')
    val ignoreCase: Boolean = flags.contains('i')
    val multiline: Boolean = flags.contains('m')
    val sticky: Boolean = flags.contains('y')
    val unicode: Boolean = flags.contains('u')
    val dotAll: Boolean = flags.contains('s')

    init {
        defineDataProperty(
            key = PropertyKey.Str("source"),
            value = JSString(if (pattern.isEmpty()) "(?:)" else pattern),
            writable = false,
            enumerable = false,
            configurable = false,
        )
        defineDataProperty(
            key = PropertyKey.Str("flags"),
            value = JSString(flags),
            writable = false,
            enumerable = false,
            configurable = false,
        )
        defineDataProperty(PropertyKey.Str("global"), JSBoolean.getJsBoolean(global), false, false, false)
        defineDataProperty(PropertyKey.Str("ignoreCase"), JSBoolean.getJsBoolean(ignoreCase), false, false, false)
        defineDataProperty(PropertyKey.Str("multiline"), JSBoolean.getJsBoolean(multiline), false, false, false)
        defineDataProperty(PropertyKey.Str("sticky"), JSBoolean.getJsBoolean(sticky), false, false, false)
        defineDataProperty(PropertyKey.Str("unicode"), JSBoolean.getJsBoolean(unicode), false, false, false)
        defineDataProperty(PropertyKey.Str("dotAll"), JSBoolean.getJsBoolean(dotAll), false, false, false)
        defineDataProperty(
            key = PropertyKey.Str("lastIndex"),
            value = JSNumber.ZERO,
            writable = true,
            enumerable = false,
            configurable = false,
        )
    }

    /** 读取 lastIndex。 */
    fun getLastIndex(): Int =
        (getOwnDataValue(PropertyKey.Str("lastIndex")) as? JSNumber)?.value?.toInt() ?: 0

    /** 写入 lastIndex。 */
    fun setLastIndex(value: Int) {
        setOwnDataValue(PropertyKey.Str("lastIndex"), JSNumber(value.toDouble()))
    }

    override fun toPrimitiveString(): String = "/$pattern/$flags"
}
