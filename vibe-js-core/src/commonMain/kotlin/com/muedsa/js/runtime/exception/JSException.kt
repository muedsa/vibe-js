package com.muedsa.js.runtime.exception

import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * JavaScript 异常（对应 throw 语句或运行时错误）。
 *
 * @property value 被抛出的任意 JS 值
 * @property stack Error 创建或首次抛出时的调用栈快照，普通值使用抛出位置
 */
class JSException(
    val value: JSValue,
    stack: List<StackFrame> = (value as? JSObject)?.errorStack ?: emptyList(),
) : Exception(messageOf(value)) {

    /**
     * 首次捕获后保持不变；环境层没有解释器时，延迟到最近的执行边界补齐。
     * Error 的内部快照已经冻结，跨万层 Promise 链传播时共享它，避免反复复制。
     */
    var stack: List<StackFrame> = if (stack === (value as? JSObject)?.errorStack) stack else stack.toList()
        private set

    init {
        preserveErrorStack()
    }

    internal fun attachStackIfAbsent(frames: List<StackFrame>) {
        if (stack.isEmpty()) stack = (value as? JSObject)?.errorStack ?: frames.toList()
        preserveErrorStack()
    }

    private fun preserveErrorStack() {
        if (value is JSObject && value.isErrorData && value.errorStack == null && stack.isNotEmpty()) {
            value.errorStack = stack
        }
    }

    /** 生成可读的错误摘要，供宿主日志使用。 */
    private val summary: String
        get() = formatErrorStack(message ?: "JavaScript error", stack)

    override fun toString(): String = summary

    companion object {
        /** 内部快照已固定，可在 Promise 转发时共享，避免长拒绝链逐层复制同一份帧。 */
        internal fun fromSnapshot(value: JSValue, frames: List<StackFrame>): JSException =
            JSException(value).also {
                if (it.stack.isEmpty()) it.stack = frames
                it.preserveErrorStack()
            }

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
            // ErrorData 的 message 可以缺省（包括 AggregateError/SuppressedError）。
            // 宿主摘要不执行 JS getter，只读取数据属性，并与 stack 的空名称/空消息格式一致。
            val message = findText(value, PropertyKey.MESSAGE)
                ?: if (value.isErrorData) "" else return value.toPrimitiveString()
            val name = findText(value, PropertyKey.NAME)
                ?: if (value.isErrorData) "Error" else return message
            return when {
                name.isEmpty() -> message
                message.isEmpty() -> name
                else -> "$name: $message"
            }
        }

        /** 沿原型链查找数据属性；访问器不能被跳过后误读为原型上的旧值。 */
        private fun findText(value: JSObject, key: PropertyKey): String? {
            var current: JSObject? = value
            while (current != null) {
                if (current.getOwnProperty(key) != null) {
                    return (current.getOwnDataValue(key) as? JSString)?.value
                }
                current = current.prototype
            }
            return null
        }
    }
}
