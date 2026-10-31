package com.muedsa.js.runtime.value

/**
 * Date 对象。
 *
 * @property timeValue 自 1970-01-01T00:00:00Z 起的毫秒数；无效日期为 NaN
 */
class JSDate(
    time: Double,
    prototype: JSObject?,
) : JSObject(prototype) {

    /** 自 1970-01-01T00:00:00Z 起的毫秒数（TimeClip 后的值）。 */
    var timeValue: Double = timeClip(time)
        set(value) {
            field = timeClip(value)
        }

    /** 是否为有效日期。 */
    val isValid: Boolean get() = !timeValue.isNaN()

    override fun toPrimitiveString(): String =
        if (isValid) "Date(${timeValue})" else "Invalid Date"

    private companion object {
        /** ES5 15.9.1.14 TimeClip：非有限或超出 ±8.64e15 返回 NaN，否则向零截断。 */
        fun timeClip(value: Double): Double {
            if (!value.isFinite() || kotlin.math.abs(value) > 8.64e15) return Double.NaN
            return kotlin.math.truncate(value) + 0.0
        }
    }
}
