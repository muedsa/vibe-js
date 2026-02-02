package com.muedsa.js.ast

/**
 * return语句，用于从函数返回值，如: return expr;
 * @property value 可选的返回表达式
 */
data class ReturnStatement(val value: Expression?) : Statement()
