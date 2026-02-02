package com.muedsa.js.ast

/**
 * Catch 子句：catch (error) { ... }
 * param: 捕获的异常变量名
 * body: 捕获块的执行语句
 */
data class CatchClause(
    val param: String?,
    val body: BlockStatement,
    val range: IntRange,
)
