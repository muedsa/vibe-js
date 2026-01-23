package com.muedsa.js.ast

/**
 * 变量声明语句，如: var x = 10; let y; const z = 20;
 * @property kind 声明类型(var, let, const)
 * @property name 变量名
 * @property initializer 可选的初始化表达式
 */
data class VarDeclaration(
    val kind: String,
    val name: String,
    val initializer: Expression?
) : Statement()