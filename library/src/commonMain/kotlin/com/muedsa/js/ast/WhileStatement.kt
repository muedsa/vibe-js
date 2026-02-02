package com.muedsa.js.ast

/**
 * while循环语句，如: while (condition) { statement }
 * @property condition 循环条件表达式
 * @property body 循环体语句
 */
data class WhileStatement(
    val condition: Expression,
    val body: Statement
) : Statement()
