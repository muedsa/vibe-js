package com.muedsa.js.diagnostics

import com.muedsa.js.lexer.LexerException
import com.muedsa.js.lexer.SourceInfo
import com.muedsa.js.lexer.SourceRange
import com.muedsa.js.parser.ParseException

/** 诊断类别。 */
enum class DiagnosticCategory {
    /** 词法阶段：非法字符、未闭合字符串/模板/正则字面量等。 */
    LEXICAL,

    /** 语法阶段：意外的 token、非法声明、严格模式早期错误等。 */
    SYNTAX,
}

/**
 * 结构化诊断。
 *
 * 词法器与解析器仍抛出原有异常（保持兼容）；该类型是给宿主 / IDE / 构建工具的稳定视图：
 * 类别、纯描述、源码范围与来源文件，不依赖异常 `message` 的 `[行:列]` 拼接格式。
 *
 * @property category 诊断类别
 * @property message 不含位置前缀的原始描述
 * @property range 出错位置（起始/结束行列与字符偏移）
 * @property sourceInfo 解析时使用的来源信息（文件名与行偏移）
 */
data class Diagnostic(
    val category: DiagnosticCategory,
    val message: String,
    val range: SourceRange,
    val sourceInfo: SourceInfo = SourceInfo(),
) {

    /** 叠加来源行偏移后的起始行（与运行时错误栈保持一致）。 */
    val line: Int get() = range.start.line + sourceInfo.lineOffset

    /** 起始列（从 1 开始）。 */
    val column: Int get() = range.start.column

    /** 单行文本：`文件名:行:列: 描述`。 */
    fun format(): String = "${sourceInfo.name}:$line:$column: $message"
}

/**
 * 把词法/语法异常转换为结构化诊断。
 *
 * @param sourceInfo 解析时使用的来源信息（对应 `Engine.parse` 的 sourceName/lineOffset）
 * @return 非词法/语法异常返回 `null`，便于调用方透传其他错误
 */
fun Throwable.toDiagnostic(sourceInfo: SourceInfo = SourceInfo()): Diagnostic? = when (this) {
    is LexerException -> Diagnostic(DiagnosticCategory.LEXICAL, detail, range, sourceInfo)
    is ParseException -> Diagnostic(DiagnosticCategory.SYNTAX, detail, range, sourceInfo)
    else -> null
}

/** [toDiagnostic] 的便捷重载：直接给出文件名与行偏移。 */
fun Throwable.toDiagnostic(sourceName: String, lineOffset: Int = 0): Diagnostic? =
    toDiagnostic(SourceInfo(sourceName, lineOffset))
