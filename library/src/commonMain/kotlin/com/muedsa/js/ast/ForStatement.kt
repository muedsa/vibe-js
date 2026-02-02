package com.muedsa.js.ast

/**
 * for循环语句，如: for (var i = 0; i < 10; i++) { statement }
 * @property init 可选的初始化语句
 * @property condition 可选的循环条件表达式
 * @property update 可选的更新表达式
 * @property body 循环体语句
 */
data class ForStatement(
    val init: Statement?,
    val condition: Expression?,
    val update: Expression?,
    val body: Statement
) : Statement()
