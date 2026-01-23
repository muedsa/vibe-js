package com.muedsa.js.ast

/**
 * 条件表达式(三元运算符)，如: condition ? expr1 : expr2
 * @property condition 条件表达式
 * @property thenBranch 条件为真时执行的表达式
 * @property elseBranch 条件为假时执行的表达式
 */
data class ConditionalExpr(
    val condition: Expression,
    val thenBranch: Expression,
    val elseBranch: Expression
) : Expression()