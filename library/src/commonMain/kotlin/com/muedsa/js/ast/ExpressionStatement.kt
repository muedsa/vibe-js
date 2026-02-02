package com.muedsa.js.ast

/**
 * 表达式语句，将表达式作为语句执行，如: x = 10;
 * @property expression 要执行的表达式
 */
data class ExpressionStatement(val expression: Expression) : Statement()
