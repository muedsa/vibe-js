package com.muedsa.js.ast

/**
 * Throw 语句：throw value;
 * 用于抛出异常
 */
data class ThrowStatement(val argument: Expression) : Statement()
