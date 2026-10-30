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
) : Exception("[$range] $message")
