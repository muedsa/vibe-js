package com.muedsa.js.ast

/**
 * 一元操作符表达式，如: -x, !flag
 * @property operator 操作符字符串
 * @property operand 操作数
 */
data class UnaryOp(
    val operator: String,
    val operand: Expression
) : Expression()
