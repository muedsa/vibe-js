package com.muedsa.js.runtime

import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSUndefined
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchDeclarationTest {

    private fun runJs(code: String): Interpreter {
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = Interpreter()
        interpreter.interpret(program)
        return interpreter
    }

    @Test
    fun `test batch var declaration`() {
        val interpreter = runJs("var a = 1, b = 2, c = 3;")
        assertEquals(1.0, (interpreter.getValue("a") as JSNumber).value)
        assertEquals(2.0, (interpreter.getValue("b") as JSNumber).value)
        assertEquals(3.0, (interpreter.getValue("c") as JSNumber).value)
    }

    @Test
    fun `test batch let declaration`() {
        val interpreter = runJs("let x = 10, y, z = 30;")
        assertEquals(10.0, (interpreter.getValue("x") as JSNumber).value)
        assertEquals(JSUndefined, interpreter.getValue("y"))
        assertEquals(30.0, (interpreter.getValue("z") as JSNumber).value)
    }

    @Test
    fun `test batch const declaration`() {
        val interpreter = runJs("const i = 100, j = 200;")
        assertEquals(100.0, (interpreter.getValue("i") as JSNumber).value)
        assertEquals(200.0, (interpreter.getValue("j") as JSNumber).value)
    }

    @Test
    fun `test batch var hoisting`() {
        val code = """
            var result = a;
            var a = 1, b = 2;
            result = result + a + b;
        """.trimIndent()
        val interpreter = runJs(code)
        // a 提升后初始为 undefined, result = undefined
        // 执行 var a=1, b=2 后，a=1, b=2
        // result = undefined + 1 + 2 = NaN (在我们的 JSNumber 实现中)
        val finalResult = interpreter.getValue("result")
        assertTrue(finalResult is JSNumber && finalResult.value.isNaN())
    }

//    @Test
//    fun `test batch declaration in for loop`() {
//        val code = """
//            var sum = 0;
//            for (var i = 0, j = 10; i < j; i++, j--) {
//                sum += i + j;
//            }
//        """.trimIndent()
//        val interpreter = runJs(code)
//        // i=0, j=10, sum=10, i=1, j=9
//        // i=1, j=9, sum=10+10=20, i=2, j=8
//        // i=2, j=8, sum=20+10=30, i=3, j=7
//        // i=3, j=7, sum=30+10=40, i=4, j=6
//        // i=4, j=6, sum=40+10=50, i=5, j=5
//        // i=5, j=5 -> loop end
//        assertEquals(50.0, (interpreter.getValue("sum") as JSNumber).value)
//    }
}
