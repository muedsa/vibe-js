package com.muedsa.js.runtime.exception

import com.muedsa.js.lexer.SourceRange

/**
 * 调用栈帧。
 *
 * @property functionName 函数名（全局脚本使用 "<global>"）
 * @property range 调用点源码范围（可选）
 */
data class StackFrame(
    val functionName: String,
    val range: SourceRange? = null,
)
