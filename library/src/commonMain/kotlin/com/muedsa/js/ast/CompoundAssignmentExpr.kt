package com.muedsa.js.ast

/**
 * 复合赋值表达式，如: x += 5, y *= 2
 * @property target 目标变量名
 * @property operator 复合操作符字符串
 * @property value 赋值表达式
 */
data class CompoundAssignmentExpr(
    val target: String,
    val operator:  String,
    val value: Expression
) : Expression()