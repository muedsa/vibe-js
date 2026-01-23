package com.muedsa.js.ast

/**
 * 赋值表达式，如: x = 10
 * @property target 目标变量名
 * @property value 赋值表达式
 */
data class AssignmentExpr(
    val target: String,
    val value: Expression
) : Expression()