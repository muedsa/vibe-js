package com.muedsa.js

import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.lexer.Token
import com.muedsa.js.lexer.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 词法分析器细粒度测试。
 */
class LexerTest {

    private fun tokenize(code: String): List<Token> = Lexer(code).tokenize()

    @Test
    fun `识别关键字与标识符`() {
        val types = tokenize("var x = true;").map { it.type }
        assertEquals(
            listOf(
                TokenType.VAR,
                TokenType.IDENTIFIER,
                TokenType.ASSIGN,
                TokenType.TRUE,
                TokenType.SEMICOLON,
                TokenType.EOF,
            ),
            types,
        )
    }

    @Test
    fun `数字字面量支持科学计数法前导小数与进制前缀`() {
        assertEquals("1e3", tokenize("1e3")[0].value)
        assertEquals(TokenType.NUMBER, tokenize("1e3")[0].type)
        assertEquals(".5", tokenize(".5")[0].value)
        assertEquals("0xff", tokenize("0xff")[0].value)
        assertEquals(TokenType.NUMBER_HEX, tokenize("0xff")[0].type)
        assertEquals("0b101", tokenize("0b101")[0].value)
        assertEquals("0o17", tokenize("0o17")[0].value)
    }

    @Test
    fun `字符串转义`() {
        val token = tokenize("'a\\nb\\t\\\\'")[0]
        assertEquals(TokenType.STRING, token.type)
        assertEquals("a\nb\t\\", token.value)
    }

    @Test
    fun `Unicode 转义与码点转义`() {
        assertEquals("A", tokenize("'\\u0041'")[0].value)
        assertEquals("😀", tokenize("'\\u{1F600}'")[0].value)
    }

    @Test
    fun `注释被忽略且换行信息保留`() {
        val tokens = tokenize("a // comment\nb")
        assertEquals("a", tokens[0].value)
        assertEquals(false, tokens[0].precededByNewline)
        assertEquals("b", tokens[1].value)
        assertEquals(true, tokens[1].precededByNewline)
    }

    @Test
    fun `多行注释中的换行也会被记录`() {
        val tokens = tokenize("a /* x\ny */ b")
        assertEquals("b", tokens[1].value)
        assertTrue(tokens[1].precededByNewline)
    }

    @Test
    fun `未闭合字符串抛出词法错误`() {
        assertFailsWith<LexerException> { tokenize("'abc") }
    }

    @Test
    fun `未闭合块注释抛出词法错误`() {
        assertFailsWith<LexerException> { tokenize("/* abc") }
    }

    @Test
    fun `非法字符抛出词法错误`() {
        assertFailsWith<LexerException> { tokenize("@") }
    }
}
