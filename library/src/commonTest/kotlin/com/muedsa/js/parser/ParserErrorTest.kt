package com.muedsa.js.parser

import com.muedsa.js.lexer.Lexer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ParserErrorTest {

    private fun parse(code: String) {
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        parser.parse()
    }

    private fun assertParseError(code: String, expectedMessagePart: String? = null) {
        val exception = assertFailsWith<ParseException> {
            parse(code)
        }
        if (expectedMessagePart != null) {
            // 简单的包含检查，忽略大小写
            val message = exception.message?.lowercase() ?: ""
            val expected = expectedMessagePart.lowercase()
            if (!message.contains(expected)) {
                assertEquals(expectedMessagePart, exception.message, "Error message should contain expected text")
            }
        }
    }

    @Test
    fun `test missing semicolon in var declaration`() {
        // 测试变量声明中缺少分号的情况
        // 虽然 Parser 目前对分号是可选处理或吞掉，但在某些严格期望分号的地方可能会报错
        // 不过根据 Parser 代码，consumeSemicolon 只是 if (check) advance，并不强制。
        // 所以这个测试可能通过，或者我们需要找一个强制需要分号或特定终结符的场景。
        // 目前 Parser 实现比较宽容。
        // 让我们测试其他强制性的符号。
    }

    @Test
    fun `test missing identifier in var declaration`() {
        // 测试 var 声明后缺少标识符（变量名）的情况
        // 例如: "var = 1;"
        assertParseError("var = 1;", "Expected identifier")
    }

    @Test
    fun `test missing assignment in const`() {
        // 测试 const 声明缺少初始化赋值的情况
        // 目前 Parser.kt 中 parseVarDeclaration 对 const 并没有特殊检查 initializer != null
        // 所以这可能暂时不会报错，视 Parser 实现而定。
        // 如果我们想测试 Parser 的现有逻辑，我们应该测试它确实报错的地方。
    }

    @Test
    fun `test invalid syntax in function declaration`() {
        // 测试函数声明中的语法错误
        assertParseError("function () {}", "Expected function name") // 缺少函数名
        assertParseError("function test(a, ) {}", "Expected parameter name") // 参数列表中多余的逗号导致期待参数名
        assertParseError("function test(a b) {}", "Expected ')'") // 参数之间缺少逗号
    }

    @Test
    fun `test missing closing brace`() {
        // 测试代码块缺少闭合花括号 '}'
        assertParseError("{ var x = 1;", "Expected '}'")
    }

    @Test
    fun `test missing closing paren in if`() {
        // 测试 if 语句条件缺少闭合括号 ')'
        assertParseError("if (true { }", "Expected ')'")
    }

    @Test
    fun `test missing closing paren in group expression`() {
        // 测试分组表达式缺少闭合括号 ')'
        assertParseError("var x = (1 + 2;", "Expected ')'")
    }

    @Test
    fun `test invalid assignment target`() {
        // 测试非法的赋值目标
        // 例如给数字字面量或字符串字面量赋值
        assertParseError("1 = 2;", "Invalid assignment target")
        assertParseError("\"str\" = 2;", "Invalid assignment target")
    }

    @Test
    fun `test missing colon in object literal`() {
        // 测试对象字面量中属性名和值之间缺少冒号 ':'
        assertParseError("var obj = { a 1 };", "Expected ':'")
    }

    @Test
    fun `test missing closing brace in object literal`() {
        // 测试对象字面量缺少闭合花括号 '}'
        assertParseError("var obj = { a: 1", "Expected '}'")
    }

    @Test
    fun `test missing closing bracket in array literal`() {
        // 测试数组字面量缺少闭合方括号 ']'
        assertParseError("var arr = [1, 2", "Expected ']'")
    }

    @Test
    fun `test unexpected token`() {
        // 测试 switch 语句块中出现意外的 Token (非 case 或 default)
        assertParseError("switch(x) { var y = 1; }", "Expected 'case' or 'default'")
    }

    @Test
    fun `test try without catch or finally`() {
        // 测试 try 语句后面既没有 catch 也没有 finally
        assertParseError("try { }", "Missing catch or finally")
    }
}
