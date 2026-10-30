package com.muedsa.js

import com.muedsa.js.ast.ArrayLiteral
import com.muedsa.js.ast.EmptyStatement
import com.muedsa.js.ast.FunctionDeclaration
import com.muedsa.js.ast.VarDeclaration
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.ParseException
import com.muedsa.js.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 语法解析器细粒度测试。
 */
class ParserTest {

    private fun parse(code: String) = Parser(Lexer(code).tokenize()).parseProgram()

    @Test
    fun `空语句`() {
        val program = parse(";;;")
        assertEquals(3, program.body.size)
        assertIs<EmptyStatement>(program.body[0])
    }

    @Test
    fun `严格模式指令`() {
        val program = parse("\"use strict\"; var a = 1;")
        assertTrue(program.strict)
    }

    @Test
    fun `函数体严格模式与嵌套继承`() {
        val program = parse(
            """
            function outer() {
                "use strict";
                function inner() {}
            }
            """.trimIndent()
        )
        val outer = assertIs<FunctionDeclaration>(program.body.single())
        assertTrue(outer.strict)
        assertEquals(1, outer.functionDeclarations.size)
        assertTrue(outer.functionDeclarations[0].strict)
    }

    @Test
    fun `变量提升信息在解析阶段收集`() {
        val program = parse(
            """
            function f() {
                var x = 1;
                if (true) { var y = 2; }
                function g() {}
            }
            """.trimIndent()
        )
        val function = assertIs<FunctionDeclaration>(program.body.single())
        assertEquals(listOf("x", "y"), function.varDeclarations)
        assertEquals(1, function.functionDeclarations.size)
        assertEquals("g", function.functionDeclarations[0].name)
    }

    @Test
    fun `数组字面量保留空洞`() {
        val program = parse("var a = [1, , 3];")
        val declaration = assertIs<VarDeclaration>(program.body.single())
        val array = assertIs<ArrayLiteral>(declaration.declarations.single().initializer)
        assertEquals(3, array.elements.size)
        assertEquals(null, array.elements[1])
    }

    @Test
    fun `return 后换行返回 undefined`() {
        val program = parse("function f() { return\n1; }")
        val function = assertIs<FunctionDeclaration>(program.body.single())
        assertEquals(2, function.body.statements.size)
    }

    @Test
    fun `语法错误包含源码位置`() {
        val error = assertFailsWith<ParseException> { parse("var = 1;") }
        assertEquals(1, error.range.start.line)
    }

    @Test
    fun `for 循环初始化与更新`() {
        val program = parse("for (var i = 0; i < 3; i++) { }")
        assertEquals(1, program.body.size)
        assertIs<com.muedsa.js.ast.ForStatement>(program.body[0])
    }
}
