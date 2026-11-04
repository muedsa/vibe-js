package com.muedsa.js

import com.muedsa.js.diagnostics.DiagnosticCategory
import com.muedsa.js.diagnostics.toDiagnostic
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.parser.ParseException
import com.muedsa.js.runtime.Engine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 诊断适配层测试：确认词法/语法异常能转换成稳定的结构化视图，
 * 且不影响原有异常类型与 message 格式。
 */
class DiagnosticTest {

    @Test
    fun `词法异常转换为词法诊断`() {
        val engine = Engine()
        val error = assertFailsWith<LexerException> {
            engine.parse("var a = 1; @", sourceName = "lex.js", lineOffset = 20)
        }
        val diagnostic = error.toDiagnostic("lex.js", lineOffset = 20)!!

        assertEquals(DiagnosticCategory.LEXICAL, diagnostic.category)
        assertEquals("lex.js", diagnostic.sourceInfo.name)
        // 源码第 1 行 + 行偏移 20
        assertEquals(21, diagnostic.line)
        assertTrue(diagnostic.message.isNotEmpty())
        // 纯描述不包含异常 message 的 [行:列] 前缀
        assertFalse(diagnostic.message.startsWith("["))
        assertTrue(diagnostic.format().startsWith("lex.js:21:"))
    }

    @Test
    fun `语法异常转换为语法诊断`() {
        val engine = Engine()
        val error = assertFailsWith<ParseException> { engine.parse("var = 1;") }
        val diagnostic = error.toDiagnostic()!!

        assertEquals(DiagnosticCategory.SYNTAX, diagnostic.category)
        assertEquals("<anonymous>", diagnostic.sourceInfo.name)
        assertTrue(diagnostic.message.isNotEmpty())
        assertFalse(diagnostic.message.startsWith("["))
        assertEquals(1, diagnostic.line)
    }

    @Test
    fun `其他异常不产生诊断`() {
        assertNull(IllegalStateException("boom").toDiagnostic())
        assertNull(RuntimeException("boom").toDiagnostic("x.js"))
    }

    @Test
    fun `诊断保留源码范围与原始异常`() {
        val engine = Engine()
        val error = assertFailsWith<LexerException> { engine.parse("var a = 1; @") }
        val diagnostic = error.toDiagnostic()!!

        assertEquals(error.range, diagnostic.range)
        assertEquals(1, diagnostic.range.start.line)
        assertTrue(diagnostic.range.start.offset >= 0)
        // 原异常仍是 LexerException，message 格式保持不变
        assertTrue(error.message!!.startsWith("["))
    }
}
