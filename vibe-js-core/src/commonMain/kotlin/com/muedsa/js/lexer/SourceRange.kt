package com.muedsa.js.lexer

/**
 * 脚本来源信息，独立于源码内部的行列位置。
 *
 * [lineOffset] 是显示行号时增加的非负偏移，例如源码第 1 行对应宿主文件第 21 行
 * 时设置为 20。AST 的 SourceRange 保留相对位置，便于预解析与源码切片复用。
 */
data class SourceInfo(
    val name: String = "<anonymous>",
    val lineOffset: Int = 0,
) {
    init {
        require(lineOffset >= 0) { "lineOffset must be >= 0" }
    }
}

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
