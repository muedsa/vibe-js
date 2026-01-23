package com.muedsa.js.ast

/**
 * if条件语句，如: if (condition) { statement1 } else { statement2 }
 * @property condition 条件表达式
 * @property consequent 条件为真时执行的语句
 * @property alternate 可选的条件为假时执行的语句
 */
data class IfStatement(
    val condition: Expression,
    val consequent: Statement,
    val alternate: Statement?
) : Statement()