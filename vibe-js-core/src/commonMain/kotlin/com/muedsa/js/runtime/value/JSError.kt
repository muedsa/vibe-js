package com.muedsa.js.runtime.value

/**
 * Error 实例。
 *
 * - message 是实例的自有属性；
 * - name 由对应原型对象提供（Error.prototype / TypeError.prototype ...）；
 * - toString 的默认行为由 Error.prototype.toString 提供（内建模块注册），
 *   这里的 toPrimitiveString 作为兜底实现。
 *
 * @property errorName 错误名称（用于兜底文本）
 * @property messageText 错误消息
 */
open class JSError(
    val errorName: String,
    val messageText: String,
    prototype: JSObject? = null,
) : JSObject(prototype) {

    init {
        isErrorData = true
        // 阶段 A：name 同时定义在实例上，保证脱离原型链（例如 Environment 内部抛错）时仍可读取
        defineDataProperty(
            PropertyKey.NAME,
            JSString(errorName),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        defineDataProperty(
            PropertyKey.MESSAGE,
            JSString(messageText),
            writable = true,
            enumerable = false,
            configurable = true,
        )
    }

    override fun toPrimitiveString(): String =
        if (messageText.isEmpty()) errorName else "$errorName: $messageText"
}
