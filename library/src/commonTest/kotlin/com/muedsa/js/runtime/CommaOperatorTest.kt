package com.muedsa.js.runtime

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.value.JSNumber
import kotlin.test.Test
import kotlin.test.assertEquals

class CommaOperatorTest {

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
    fun `test comma operator in expression`() {
        val i = eval("var a = (1, 2, 3);")
        assertEquals(3.0, (i.getValue("a") as JSNumber).value)
    }

    @Test
    fun `test comma operator evaluation order`() {
        val i = eval("var k = 0; var a = (k = 1, k = 2, k = 3);")
        assertEquals(3.0, (i.getValue("a") as JSNumber).value)
        assertEquals(3.0, (i.getValue("k") as JSNumber).value)
    }

    @Test
    fun `test comma operator in for loop`() {
        val i = eval("""
            var sum = 0;
            for (var i = 0, j = 10; i < 5; i++, j--) {
                sum = sum + i + j;
            }
        """.trimIndent())
        assertEquals(50.0, (i.getValue("sum") as JSNumber).value)
    }

    @Test
    fun `test comma operator in function call`() {
         val i = eval("""
             function sum(a, b) {
                 return a + b;
             }
             var res = sum(1, 2);
         """.trimIndent())
         assertEquals(3.0, (i.getValue("res") as JSNumber).value)
    }

    @Test
    fun `test comma operator with parenthesis in function call`() {
         val i = eval("""
             function id(x) { return x; }
             var res = id((1, 2));
         """.trimIndent())
         assertEquals(2.0, (i.getValue("res") as JSNumber).value)
    }
}
