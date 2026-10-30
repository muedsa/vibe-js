package com.muedsa.js.runtime.exception

import com.muedsa.js.runtime.value.JSValue

/**
 * JavaScript 异常（对应 throw 语句或运行时错误）。
 *
 * @property value 被抛出的任意 JS 值
 * @property stack 抛出时的调用栈快照
 */
class JSException(
    val value: JSValue,
    val stack: List<StackFrame> = emptyList(),
) : Exception(messageOf(value)) {

    /** 生成可读的错误摘要，供宿主日志使用。 */
    private val summary: String
        get() = buildString {
            append(message ?: "JavaScript error")
            if (stack.isNotEmpty()) {
                append('\n')
                stack.asReversed().forEach { frame ->
                    append("    at ")
                    append(frame.functionName)
                    frame.range?.let { append(" (").append(it).append(')') }
                    append('\n')
                }
            }
        }

    override fun toString(): String = summary

    companion object {
        private fun messageOf(value: JSValue): String = try {
            value.toPrimitiveString()
        } catch (_: Throwable) {
            "JavaScript error"
        }
    }
}
