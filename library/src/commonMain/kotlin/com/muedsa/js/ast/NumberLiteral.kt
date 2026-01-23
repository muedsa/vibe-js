package com.muedsa.js.ast

/**
 * 数字字面量表达式，如: 42, 3.14
 */
data class NumberLiteral(
    val value: Double
) : Expression()