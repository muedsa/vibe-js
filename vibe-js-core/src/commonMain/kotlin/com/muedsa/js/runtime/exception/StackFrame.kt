package com.muedsa.js.runtime.exception

import com.muedsa.js.lexer.SourceRange
import com.muedsa.js.lexer.SourceInfo

/**
 * 调用栈帧。
 *
 * @property functionName 函数名（全局脚本使用 "<global>"）
 * @property range 当前帧的执行位置；调用方帧停留在调用点（可选）
 * @property sourceInfo 此帧所执行代码的定义来源，行偏移只在格式化时叠加
 */
data class StackFrame(
    val functionName: String,
    val range: SourceRange? = null,
    val sourceInfo: SourceInfo = SourceInfo(),
    /** 标记 async 创建时保留的调用方边界，不对应仍占用的物理栈。 */
    val isAsync: Boolean = false,
)

/**
 * 不可变帧链：同步压栈只冻结当前帧，async 可以共享祖先而无需反复复制整条栈。
 * 不保存 Interpreter 或 Environment，避免错误对象保留整套运行时状态。
 */
internal class StackFrameLink(val frame: StackFrame, val parent: StackFrameLink?)

/** JS stack 与 Kotlin JSException 共用的格式；保存顺序为最外层到最内层。 */
internal fun formatErrorStack(header: String, frames: List<StackFrame>): String = buildString {
    append(header)
    for (frame in frames.asReversed()) {
        append("\n    at ")
        if (frame.isAsync) append("async ")
        append(frame.functionName.ifEmpty { "anonymous" })
        val range = frame.range
        if (range != null && range.start.line > 0) {
            append(" (")
            // 来源名是诊断文本，不把其中的换行解释成额外堆栈帧。
            append(frame.sourceInfo.name.replace("\r", "\\r").replace("\n", "\\n"))
            append(':').append(range.start.line.toLong() + frame.sourceInfo.lineOffset)
            append(':').append(range.start.column).append(')')
        }
    }
}
