package com.muedsa.js.ast

/**
 * 函数调用表达式，如: func(), obj.method(arg1, arg2)
 * @property callee 被调用的函数表达式
 * @property arguments 实参列表
 */
data class CallExpr(
    val callee: Expression,
    val arguments:  List<Expression>
) : Expression()