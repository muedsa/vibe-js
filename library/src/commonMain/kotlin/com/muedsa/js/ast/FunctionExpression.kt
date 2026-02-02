package com.muedsa.js.ast

/**
 * 函数表达式，如: var add = function(a, b) { return a + b; }
 * @property name 函数名 (可选)
 * @property params 形参列表
 * @property body 函数体语句块
 */
data class FunctionExpression(
    val name: String?,
    val params: List<String>,
    val body: BlockStatement
) : Expression()
