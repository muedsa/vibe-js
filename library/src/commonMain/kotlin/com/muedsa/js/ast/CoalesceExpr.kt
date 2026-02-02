package com.muedsa.js.ast

/**
 * 空值合并表达式，如: value ?? defaultValue
 * @property left 左侧表达式
 * @property right 左侧为空时使用的默认表达式
 */
data class CoalesceExpr(
    val left: Expression,
    val right: Expression
) : Expression()
