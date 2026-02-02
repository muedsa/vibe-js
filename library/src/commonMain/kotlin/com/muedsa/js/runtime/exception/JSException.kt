package com.muedsa.js.runtime.exception

import com.muedsa.js.runtime.value.JSValue

/**
 * 用于表示 throw 语句抛出的异常
 * @param value 抛出的值，可以是任何 JSValue
 */
class JSException(
    val value: JSValue,
    val stackTrace: List<StackFrame> = emptyList(),
) : Throwable() {
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(value.toPrimitiveString())
        if (stackTrace.isNotEmpty()) {
            sb.append("\n")
            stackTrace.reversed().forEach { frame ->
                sb.append(frame).append("\n")
            }
        }
        return sb.toString()
    }
}
