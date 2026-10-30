package com.muedsa.js.lexer

/**
 * 源码中的位置（行、列、字符偏移）。
 *
 * 行号与列号均从 1 开始，offset 从 0 开始，便于与 IDE / 编辑器习惯保持一致。
 */
data class SourcePosition(
    val line: Int,
    val column: Int,
    val offset: Int,
)

/**
 * 源码范围：由起始位置和结束位置组成（结束位置是"最后一个字符之后"的位置）。
 *
 * 词法单元（Token）与抽象语法树（AST）节点都携带该信息，
 * 用于运行时错误定位、语法错误提示以及后续的 source map 扩展。
 */
data class SourceRange(
    val start: SourcePosition,
    val end: SourcePosition,
) {
    companion object {
        /** 未知位置，用于自动生成的节点（例如内部合成代码）。 */
        val NONE = SourceRange(SourcePosition(0, 0, 0), SourcePosition(0, 0, 0))
    }

    override fun toString(): String = "${start.line}:${start.column}"
}
