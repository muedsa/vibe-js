package com.muedsa.js.ast

/**
 * 字符串字面量表达式，如: "hello", 'world'
 */
data class StringLiteral(val value: String) : Expression()
