package com.muedsa.js.runtime

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LetConstTest {

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
    fun `test let in if block`() {
        // 测试 if 块中的 let 具有块级作用域，不会泄漏到外部
        // 在 if 块内声明的 let 变量 x 和 z 应该只在块内可见
        val i = eval("""
            var x = 1;
            var y = 0;
            if (true) {
                let x = 2; // 遮蔽外部的 x (Shadowing outer x)
                let z = 3;
                y = x; // 这里应该引用内部的 x (值为 2)
            }
            // 此时 x 应该恢复为外部的值 1，z 应该未定义
        """.trimIndent())

        assertEquals(1.0, (i.getValue("x") as JSNumber).value)
        assertEquals(2.0, (i.getValue("y") as JSNumber).value)

        // 验证 z 在外部不可见，访问应抛出错误 (ReferenceError)
        val lexer = Lexer("z;")
        val parser = Parser(lexer.tokenize())
        val program = parser.parse()

        assertFailsWith<JSException> {
            i.interpret(program)
        }
    }

    @Test
    fun `test let in while block`() {
        // 测试 while 循环体内的 let 具有块级作用域
        val i = eval("""
            var i = 0;
            var captured = 0;
            while (i < 1) {
                let temp = 100;
                captured = temp;
                i++;
            }
        """.trimIndent())

        assertEquals(100.0, (i.getValue("captured") as JSNumber).value)

        // 验证 temp 在外部不可见，访问应抛出错误
        val lexer = Lexer("temp;")
        val parser = Parser(lexer.tokenize())
        val program = parser.parse()

        assertFailsWith<JSException> {
            i.interpret(program)
        }
    }

    @Test
    fun `test let redeclaration error`() {
        // 测试同一作用域内重复声明 let 应报错 (SyntaxError)
        assertFailsWith<JSException> {
            eval("""
                let a = 1;
                let a = 2;
            """.trimIndent())
        }
    }

    @Test
    fun `test var and let conflict`() {
        // 测试 let 和 var 在同一作用域重名冲突应报错
        assertFailsWith<JSException> {
            eval("""
                var a = 1;
                let a = 2;
            """.trimIndent())
        }

        assertFailsWith<JSException> {
            eval("""
                let b = 1;
                var b = 2;
            """.trimIndent())
        }
    }

    @Test
    fun `test tdz`() {
        // 测试暂时性死区 (TDZ): 在 let 定义前访问应报错
        // 这里的机制是：let 不会像 var 那样提升并初始化为 undefined，
        // 所以在声明执行前，变量在环境中不存在，访问会导致 ReferenceError。
        assertFailsWith<JSException> {
            eval("""
                var a = b; // b 在 let 声明前被访问
                let b = 1;
            """.trimIndent())
        }
    }

    @Test
    fun `test const basic`() {
        // 测试 const 的基本功能：常量声明与块级作用域
        val i = eval("""
            const PI = 3.14;
            {
                const PI = 3.14159; // 块级作用域内的遮蔽 (Shadowing)
            }
        """.trimIndent())
        assertEquals(3.14, (i.getValue("PI") as JSNumber).value)
    }

    @Test
    fun `test const reassignment error`() {
        // 测试 const 重新赋值应报错 (TypeError)
        assertFailsWith<JSException> {
            eval("""
                const a = 1;
                a = 2;
            """.trimIndent())
        }
    }

    @Test
    fun `test const redeclaration error`() {
        // 测试 const 重复声明应报错
        assertFailsWith<JSException> {
            eval("""
                const a = 1;
                const a = 2;
            """.trimIndent())
        }
    }
}
