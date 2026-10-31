package com.muedsa.js.runtime.value

/**
 * Symbol 原始值。
 *
 * 阶段 A 仅提供类型与属性键支持，内建 Symbol 对象在后续阶段注册。
 * 符号的唯一性由引用决定，因此不重写 equals/hashCode。
 *
 * @property registered 是否由 Symbol.for 创建（注册符号不能作为 WeakMap/WeakSet 键）
 */
class JSSymbol(
    val description: String?,
    val registered: Boolean = false,
) : JSValue() {

    override val isPrimitive: Boolean = true

    override fun toPrimitiveBoolean(): Boolean = true

    /** Symbol 不能隐式转换为数字，这里返回 NaN 作为占位。 */
    override fun toPrimitiveNumber(): Double = Double.NaN

    /** 调试用文本；规范的隐式 ToString 会抛出 TypeError（由 Conversions 处理）。 */
    override fun toPrimitiveString(): String =
        if (description == null) "Symbol()" else "Symbol($description)"
}
