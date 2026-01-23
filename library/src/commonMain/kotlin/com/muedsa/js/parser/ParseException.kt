package com.muedsa.js.parser

import com.muedsa.js.lexer.Token

/**
 * 解析错误异常，包含错误位置信息
 * @param message 错误消息
 * @param line 错误所在行号
 * @param column 错误所在列号
 * @param token 导致错误的令牌
 */
class ParseException(
    message: String,
    val line: Int,
    val column: Int,
    val token: Token? = null,
) : Exception("[$line:$column] $message (Token: ${token?.value ?: "EOF"})")