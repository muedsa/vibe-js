package com.muedsa.js.ast

/**
 * 语句块，包含多个语句，如: { statement1; statement2; }
 * @property statements 语句列表
 */
data class BlockStatement(val statements: List<Statement>) : Statement()
