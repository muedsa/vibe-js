package com.muedsa.js.ast

/**
 * 函数声明语句，如: function add(a, b) { return a + b; }
 * @property name 函数名
 * @property params 形参列表
 * @property body 函数体语句块
 */
data class FunctionDeclaration(
    val name: String,
    val params: List<String>,
    val body: BlockStatement
) : Statement()