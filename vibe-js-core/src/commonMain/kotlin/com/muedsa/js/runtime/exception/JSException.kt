package com.muedsa.js.runtime.exception

import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

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
            when (value) {
                // 普通对象默认 ToString 是 [object Object]，会吞掉抛出的错误详情；
                // 这里优先读取 name/message 便于宿主日志与测试报告定位问题
                is JSObject -> describeObject(value)
                else -> value.toPrimitiveString()
            }
        } catch (_: Throwable) {
            "JavaScript error"
        }

        private fun describeObject(value: JSObject): String {
            val message = (value.getOwnDataValue(PropertyKey.MESSAGE) as? JSString)?.value
                ?: return value.toPrimitiveString()
            val name = findName(value)
            return if (name == null) message else "$name: $message"
        }

        /** 沿原型链查找 name（例如 Error 子类原型上的 "TypeError"）。 */
        private fun findName(value: JSObject): String? {
            var current: JSObject? = value
            while (current != null) {
                val name = (current.getOwnDataValue(PropertyKey.NAME) as? JSString)?.value
                if (name != null) return name
                current = current.prototype
            }
            return null
        }
    }
}
