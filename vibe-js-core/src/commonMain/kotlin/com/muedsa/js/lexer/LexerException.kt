package com.muedsa.js.lexer

/**
 * 词法错误。
 *
 * 所有词法阶段的错误统一使用该异常类型，避免出现 RuntimeException 等宿主异常，
 * 从而保证上层（Parser / 运行时）可以用一致的方式处理错误。
 *
 * @property range 错误发生的源码范围
 */
class LexerException(
    message: String,
    val range: SourceRange,
) : Exception("[$range] $message")
