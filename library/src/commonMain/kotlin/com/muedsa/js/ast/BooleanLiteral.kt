package com.muedsa.js.ast

/**
 * 布尔字面量表达式，如: true, false
 */
data class BooleanLiteral(val value: Boolean) : Expression()