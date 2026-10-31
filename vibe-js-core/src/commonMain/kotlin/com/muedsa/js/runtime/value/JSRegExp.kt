package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.regex.JsRegex

/**
 * 正则表达式对象。
 *
 * 使用 [JsRegex] 作为匹配引擎（优先 Kotlin Regex，必要时回退到纯 Kotlin 引擎），
 * 保留 JS 的 source/flags 与 lastIndex 语义。
 */
class JSRegExp(
    var pattern: String,
    var flags: String,
    var matcher: JsRegex,
    prototype: JSObject?,
) : JSObject(prototype) {

    val global: Boolean get() = flags.contains('g')
    val ignoreCase: Boolean get() = flags.contains('i')
    val multiline: Boolean get() = flags.contains('m')
    val sticky: Boolean get() = flags.contains('y')
    val unicode: Boolean get() = flags.contains('u')
    val dotAll: Boolean get() = flags.contains('s')
    val hasIndices: Boolean get() = flags.contains('d')

    init {
        // 规范：source/flags/global 等均为原型访问器（读取内部槽）；
        // 实例只拥有 lastIndex 自有属性
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

    /** 规范 flags 的规范顺序（d g i m s u v y；引擎仅支持其中部分）。 */
    fun canonicalFlags(): String = FLAG_ORDER.filter { flags.contains(it) }

    override fun toPrimitiveString(): String = "/$pattern/${canonicalFlags()}"

    private companion object {
        const val FLAG_ORDER = "dgimsuvy"
    }
}
