package com.muedsa.js.parser

import com.muedsa.js.lexer.SourceRange

/**
 * 语法错误异常。
 *
 * @property range 错误发生的源码范围
 */
class ParseException(
    message: String,
    val range: SourceRange,
) : Exception("[$range] $message") {

    /**
     * 不含位置前缀的原始错误描述。
     *
     * 异常自身的 message 带 `[行:列]` 前缀（便于直接打印），
     * 诊断适配层需要拆分出纯描述，因此单独保留一份。
     */
    val detail: String = message
}
