package com.muedsa.js.lexer

data class Token(
    val type: TokenType,
    val value: String,
    val line: Int,
    val column: Int,
    val range: IntRange = IntRange.EMPTY
)