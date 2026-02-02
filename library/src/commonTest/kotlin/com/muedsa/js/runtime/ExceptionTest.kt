package com.muedsa.js.runtime

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExceptionTest {

    private fun eval(code: String): Interpreter {
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = createRuntime()
        interpreter.interpret(program)
        return interpreter
    }

    @Test
    fun `test try catch`() {
        // 测试基本的 try-catch 异常捕获机制
        val i = eval("""
            var res = "";
            try {
                throw "error";
            } catch (e) {
                res = "caught: " + e;
            }
        """.trimIndent())
        assertEquals("caught: error", (i.getValue("res") as JSString).value)
    }

    @Test
    fun `test finally`() {
        // 测试 finally 块的执行（无论是否有异常）
        val i = eval("""
            var res = "";
            try {
                res += "try";
            } finally {
                res += "finally";
            }
        """.trimIndent())
        assertEquals("tryfinally", (i.getValue("res") as JSString).value)
    }

    @Test
    fun `test throw in catch`() {
        // 测试 catch 块中再次抛出异常的情况
        assertFailsWith<JSException> {
            eval("""
                try {
                    throw "e1";
                } catch(e) {
                    throw "e2";
                }
            """.trimIndent())
        }
    }

    @Test
    fun `test return in try finally`() {
        // 测试 try 块中包含 return 语句时，finally 块是否仍被执行（验证副作用）
        val i = eval("""
            var sideEffect = 0;
            function test() {
                try {
                    return 1;
                } finally {
                    sideEffect = 1;
                }
            }
            var res = test();
        """.trimIndent())
        assertEquals(1.0, (i.getValue("sideEffect") as com.muedsa.js.runtime.value.JSNumber).value)
        // Check result if possible, but here we check side effect.
        // The return value check is implicitly done if execution continues correct.
    }

    @Test
    fun `test error in finally overrides return`() {
        // 测试 finally 块中抛出异常时，是否会覆盖 try 块中的 return 语句
        // 预期结果：函数不返回 "ok"，而是抛出 "fail" 异常
        val exception = assertFailsWith<JSException> {
            eval("""
                function test() {
                    try {
                        return "ok";
                    } finally {
                        throw "fail";
                    }
                }
                test();
            """.trimIndent())
        }
        assertEquals("fail", (exception.value as JSString).value)
    }
}
