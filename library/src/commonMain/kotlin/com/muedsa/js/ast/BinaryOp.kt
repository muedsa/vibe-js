package com.muedsa.js.ast

/**
 * 二元操作符表达式，如: a + b, x > y
 * @property left 左操作数
 * @property operator 操作符字符串
 * @property right 右操作数
 */
data class BinaryOp(
    val left: Expression,
    val operator: String,
    val right: Expression
) : Expression()
