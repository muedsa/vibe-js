package com.muedsa.js.ast

/**
 * 更新表达式，如: x++, ++y, x--, --y
 * @property operator 操作符字符串(++或--)
 * @property argument 变量名
 * @property prefix 是否为前缀形式(true: ++x, false: x++)
 */
data class UpdateExpr(
    val operator: String,
    val argument: Expression,
    val prefix: Boolean
) : Expression()
