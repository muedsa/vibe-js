package com.muedsa.js

import com.muedsa.js.lexer.LexerException
import com.muedsa.js.parser.ParseException
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 词法与语法错误路径、以及正则/除号、ASI 等容易出错的解析场景。
 */
class ParserEdgeTest {

    // ================================================================
    // 词法边界
    // ================================================================

    @Test
    fun `正则字面量与除号可正确区分`() = runTest {
        val runtime = TestRuntime()
        assertEquals(5.0, runtime.evalNumber("var a = 10, b = 2; a / b / 1"))
        assertEquals(1.0, runtime.evalNumber("(1 + 2) / 3"))
        assertEquals(2.0, runtime.evalNumber("function f(x) { return x / 2; } f(4)"))
        assertEquals(true, runtime.evalBoolean("var r = /a\\/b/; r.test('a/b')"))
        assertEquals(true, runtime.evalBoolean("var x = 4; var r = /a/; x / 2 === 2 && r.test('a')"))
    }

    @Test
    fun `未闭合正则与模板字符串抛词法错误`() {
        assertFailsWith<LexerException> {
            com.muedsa.js.lexer.Lexer("var r = /abc").tokenize()
        }
        assertFailsWith<LexerException> {
            com.muedsa.js.lexer.Lexer("`abc").tokenize()
        }
        assertFailsWith<LexerException> {
            com.muedsa.js.lexer.Lexer("`a\${1}").tokenize()
        }
    }

    @Test
    fun `字符串与模板的转义序列`() = runTest {
        val runtime = TestRuntime()
        assertEquals("A", runtime.evalString("'\\x41'"))
        assertEquals("\u0000", runtime.evalString("'\\0'"))
        assertEquals("a\nb", runtime.evalString("`a\nb`"))
        assertEquals("a`b", runtime.evalString("`a\\`b`"))
        assertEquals("1:2", runtime.evalString("var a = 1; `\${a}:\${2}`"))
    }

    @Test
    fun `关键字可以作为属性名`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            6.0,
            runtime.evalNumber("var o = { if: 1, class: 2, default: 3 }; o.if + o.class + o.default")
        )
        assertEquals(1.0, runtime.evalNumber("var o = { var: 1 }; o['var']"))
    }

    @Test
    fun `数字字面量各进制与前导小数`() = runTest {
        val runtime = TestRuntime()
        assertEquals(255.0, runtime.evalNumber("0xff"))
        assertEquals(5.0, runtime.evalNumber("0b101"))
        assertEquals(15.0, runtime.evalNumber("0o17"))
        assertEquals(0.5, runtime.evalNumber(".5"))
        assertEquals(1000.0, runtime.evalNumber("1e3"))
    }

    // ================================================================
    // 语法错误
    // ================================================================

    @Test
    fun `非法赋值与自增目标`() {
        val parser = { code: String -> com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram() }
        assertFailsWith<ParseException> { parser("1 = 2;") }
        assertFailsWith<ParseException> { parser("var a = 1; ++a + 1 = 2;") }
        assertFailsWith<ParseException> { parser("++1;") }
        assertFailsWith<ParseException> { parser("a++ ++;") }
    }

    @Test
    fun `for 头部与 for-in of 左值错误`() {
        val parser = { code: String -> com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram() }
        assertFailsWith<ParseException> { parser("for (foo() in {}) {}") }
        assertFailsWith<ParseException> { parser("for (var i = 0; i < 1 i++) {}") }
        assertFailsWith<ParseException> { parser("for (var i = 0; i < 1; i++ {}") }
        assertFailsWith<ParseException> { parser("for (foo() of []) {}") }
    }

    @Test
    fun `try switch 对象字面量与类的语法错误`() {
        val parser = { code: String -> com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram() }
        assertFailsWith<ParseException> { parser("try {}") }
        assertFailsWith<ParseException> { parser("switch (1) { foo: }") }
        assertFailsWith<ParseException> { parser("var o = { a: };") }
        assertFailsWith<ParseException> { parser("class A { m( }") }
        assertFailsWith<ParseException> { parser("function f(a b) {}") }
        assertFailsWith<ParseException> { parser("if (1 { }") }
    }

    @Test
    fun `new target 与模板表达式错误`() {
        val parser = { code: String -> com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram() }
        assertFailsWith<ParseException> { parser("new.foo;") }
        assertFailsWith<ParseException> { parser("var s = `\${}`;") }
    }

    @Test
    fun `const 与解构声明必须初始化`() {
        val parser = { code: String -> com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram() }
        assertFailsWith<ParseException> { parser("const a;") }
        assertFailsWith<ParseException> { parser("var [a];") }
        assertFailsWith<ParseException> { parser("let {a};") }
    }

    @Test
    fun `严格模式删除标识符抛 SyntaxError`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> {
            runtime.eval("\"use strict\"; var x = 1; delete x;")
        }
        assertEquals("SyntaxError", (error.value as JSError).errorName)
    }

    @Test
    fun `顶层 break continue 与未知标签抛 SyntaxError`() = runTest {
        val runtime = TestRuntime()
        val breakError = assertFailsWith<JSException> { runtime.eval("break;") }
        assertEquals("SyntaxError", (breakError.value as JSError).errorName)
        val continueError = assertFailsWith<JSException> { runtime.eval("continue;") }
        assertEquals("SyntaxError", (continueError.value as JSError).errorName)
        val labelError = assertFailsWith<JSException> {
            runtime.eval("outer: { break missing; }")
        }
        assertEquals("SyntaxError", (labelError.value as JSError).errorName)
    }

    @Test
    fun `类外使用 super 抛 SyntaxError`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> { runtime.eval("super.x;") }
        assertEquals("SyntaxError", (error.value as JSError).errorName)
    }

    @Test
    fun `非法正则标志在运行时抛 SyntaxError`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> { runtime.eval("var r = /a/z;") }
        assertEquals("SyntaxError", (error.value as JSError).errorName)
    }

    // ================================================================
    // 自动分号插入
    // ================================================================

    @Test
    fun `ASI 在函数调用与后缀运算中的行为`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            1.0,
            runtime.evalNumber("var b = function (x) { return x; }; var a; a = b\n(1); a")
        )
        assertEquals(
            3.0,
            runtime.evalNumber("var a = 1, b = 2; a\n++b; b")
        )
        assertEquals(
            "undefined",
            runtime.evalString("function f() { return\n1; } typeof f()")
        )
    }
}
