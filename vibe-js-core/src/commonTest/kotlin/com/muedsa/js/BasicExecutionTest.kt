package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 基础执行能力：字面量、运算、变量、函数、闭包、控制流。
 */
class BasicExecutionTest {

    @Test
    fun `算术运算与优先级`() = runTest {
        val runtime = TestRuntime()
        assertEquals(7.0, runtime.evalNumber("1 + 2 * 3"))
        assertEquals(8.0, runtime.evalNumber("2 ** 3"))
        assertEquals(1.0, runtime.evalNumber("7 % 3"))
        assertEquals(-1.0, runtime.evalNumber("1 - 2"))
    }

    @Test
    fun `字符串拼接`() = runTest {
        val runtime = TestRuntime()
        assertEquals("a1", runtime.evalString("'a' + 1"))
        assertEquals("12", runtime.evalString("1 + '2'"))
        assertEquals("1[object Object]", runtime.evalString("1 + {}"))
        assertEquals("1,23", runtime.evalString("[1, 2] + [3]"))
    }

    @Test
    fun `变量声明与作用域`() = runTest {
        val runtime = TestRuntime()
        assertEquals(6.0, runtime.evalNumber("var a = 1; let b = 2; const c = 3; a + b + c"))
        assertEquals(1.0, runtime.evalNumber("let a = 1; { let a = 2; } a"))
        assertEquals(2.0, runtime.evalNumber("var x = 1; { var x = 2; } x"))
    }

    @Test
    fun `函数声明与闭包`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            3.0,
            runtime.evalNumber(
                "function add(a, b) { return a + b; } add(1, 2)"
            )
        )
        assertEquals(
            3.0,
            runtime.evalNumber(
                "function outer(x) { return function (y) { return x + y; }; } outer(1)(2)"
            )
        )
        assertEquals(
            120.0,
            runtime.evalNumber(
                "function fact(n) { if (n <= 1) { return 1; } return n * fact(n - 1); } fact(5)"
            )
        )
    }

    @Test
    fun `变量提升`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("var r = f(); function f() { return 1; } r"))
        assertEquals("undefined", runtime.evalString("typeof missingVariable"))
    }

    @Test
    fun `循环与跳转`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            10.0,
            runtime.evalNumber("var sum = 0; for (var i = 0; i <= 4; i++) { sum += i; } sum")
        )
        assertEquals(
            3.0,
            runtime.evalNumber(
                "var count = 0; for (var i = 0; i < 5; i++) { if (i === 2) { continue; } if (i === 4) { break; } count++; } count"
            )
        )
        assertEquals(
            6.0,
            runtime.evalNumber(
                "var sum = 0; var i = 0; while (i < 4) { sum += i; i++; } sum"
            )
        )
    }

    @Test
    fun `switch 的 default 位置不影响匹配`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "two",
            runtime.evalString(
                """
                var r = "";
                switch (2) {
                    default: r = "default"; break;
                    case 2: r = "two"; break;
                }
                r;
                """.trimIndent()
            )
        )
        assertEquals(
            "fallthrough",
            runtime.evalString(
                """
                var r = "";
                switch (1) {
                    case 1: r += "fall";
                    case 2: r += "through"; break;
                }
                r;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `空语句与自动分号`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("function f() {}; var a = 1; a"))
        assertEquals(
            "undefined",
            runtime.evalString("function f() { return\n1; } typeof f()")
        )
    }

    @Test
    fun `逻辑运算短路`() = runTest {
        val runtime = TestRuntime()
        assertEquals("x", runtime.evalString("0 || 'x'"))
        assertEquals(2.0, runtime.evalNumber("1 && 2"))
        assertEquals(1.0, runtime.evalNumber("null ?? 1"))
        assertEquals(0.0, runtime.evalNumber("0 ?? 1"))
    }

    @Test
    fun `类型判断与相等`() = runTest {
        val runtime = TestRuntime()
        assertEquals("object", runtime.evalString("typeof []"))
        assertEquals("object", runtime.evalString("typeof null"))
        assertEquals("function", runtime.evalString("typeof function () {}"))
        assertEquals("undefined", runtime.evalString("typeof notDefinedAnywhere"))
        assertEquals(false, runtime.evalBoolean("NaN === NaN"))
        assertEquals(true, runtime.evalBoolean("null == undefined"))
        assertEquals(false, runtime.evalBoolean("null === undefined"))
    }

    @Test
    fun `catch 未声明标识符之后的继续执行`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "ReferenceError",
            runtime.evalString("var n = ''; try { x; } catch (e) { n = e.name; } n")
        )
    }
}
