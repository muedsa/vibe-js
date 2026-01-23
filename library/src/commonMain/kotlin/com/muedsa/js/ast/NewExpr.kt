package com.muedsa.js.ast

/**
 * new表达式，用于创建对象实例，如: new Person()
 * @property objConstructor 构造函数表达式
 * @property arguments 构造函数参数列表
 */
data class NewExpr(
    val objConstructor: Expression,
    val arguments:  List<Expression>
) : Expression()