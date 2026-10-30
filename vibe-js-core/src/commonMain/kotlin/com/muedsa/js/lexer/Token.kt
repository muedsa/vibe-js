package com.muedsa.js.lexer

/**
 * 模板字符串的组成部分。
 */
sealed class TemplatePart {
    /** 字面文本段。 */
    data class Text(val value: String, val raw: String = value) : TemplatePart()

    /** `${...}` 中的表达式源码（由 Parser 递归解析）。 */
    data class ExpressionSource(val source: String) : TemplatePart()
}

/**
 * 词法单元（Token）。
 *
 * @property type Token 类型
 * @property value 原始文本（字符串字面量保存解码后的值）
 * @property range 源码范围
 * @property precededByNewline 该 Token 之前是否出现过换行。
 *           换行不生成独立 Token，而是记录在此字段上，
 *           供 Parser 实现自动分号插入（ASI）与 `return` 换行规则。
 * @property regexFlags 正则字面量的 flags（仅 REGEX 使用）
 * @property templateParts 模板字符串分段（仅 TEMPLATE 使用）
 */
data class Token(
    val type: TokenType,
    val value: String,
    val range: SourceRange,
    val precededByNewline: Boolean = false,
    val regexFlags: String = "",
    val templateParts: List<TemplatePart> = emptyList(),
) {
    /** 起始行号（1 开始），保留该便捷属性便于错误信息与测试使用。 */
    val line: Int get() = range.start.line

    /** 起始列号（1 开始）。 */
    val column: Int get() = range.start.column

    override fun toString(): String = "${type.name}('$value')@$range"
}
