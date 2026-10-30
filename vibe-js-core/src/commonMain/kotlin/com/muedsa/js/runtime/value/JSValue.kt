package com.muedsa.js.runtime.value

/**
 * JavaScript 运行时值的基类。
 *
 * 设计说明：
 * - 原始值（Number/String/Boolean/Null/Undefined/Symbol）直接继承 [JSValue]，
 *   不再像旧实现那样继承 JSObject；成员访问通过"包装对象/原型查找"完成，
 *   这样 `===`、`typeof`、属性可枚举性等语义更接近规范。
 * - 对象的属性存储、原型链由 [JSObject] 负责。
 */
sealed class JSValue {

    /** 是否为原始值。 */
    abstract val isPrimitive: Boolean

    /** 转换为布尔值（ToBoolean 语义）。 */
    abstract fun toPrimitiveBoolean(): Boolean

    /** 转换为数字（ToNumber 语义；对象由解释器通过 ToPrimitive 处理）。 */
    abstract fun toPrimitiveNumber(): Double

    /** 转换为字符串（ToString 语义；对象由解释器通过 ToPrimitive 处理）。 */
    abstract fun toPrimitiveString(): String

    /** 调试输出；即使是对象也尽量返回可读文本。 */
    override fun toString(): String = try {
        toPrimitiveString()
    } catch (_: Throwable) {
        "[object]"
    }
}
