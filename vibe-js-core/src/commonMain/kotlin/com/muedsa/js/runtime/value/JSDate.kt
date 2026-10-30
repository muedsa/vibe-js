package com.muedsa.js.runtime.value

/**
 * Date 对象。
 *
 * @property timeValue 自 1970-01-01T00:00:00Z 起的毫秒数；无效日期为 NaN
 */
class JSDate(
    var timeValue: Double,
    prototype: JSObject?,
) : JSObject(prototype) {

    /** 是否为有效日期。 */
    val isValid: Boolean get() = !timeValue.isNaN()

    override fun toPrimitiveString(): String =
        if (isValid) "Date(${timeValue})" else "Invalid Date"
}
