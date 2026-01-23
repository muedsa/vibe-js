package com.muedsa.js.ast

/**
 * Try 语句：try { ... } catch (e) { ... } finally { ... }
 * block: try 块的执行语句
 * handler: catch 子句（可选）
 * finalizer:  finally 块的执行语句（可选）
 */
data class TryStatement(
    val block: BlockStatement,
    val handler: CatchClause?,
    val finalizer: BlockStatement?
) : Statement()