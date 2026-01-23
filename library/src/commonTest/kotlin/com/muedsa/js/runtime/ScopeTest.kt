package com.muedsa.js.runtime

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.value.JSNumber
import kotlin.test.Test
import kotlin.test.assertEquals

class ScopeTest {

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
    fun `test hoisting`() {
        // Var hoisting
        val i = eval("""
            var a = x;
            var x = 10;
        """.trimIndent())
        // x is hoisted but initialized to undefined.
        // So a = undefined.
        assertEquals("undefined", i.getValue("a").toPrimitiveString())
        assertEquals(10.0, (i.getValue("x") as JSNumber).value)
    }

    @Test
    fun `test function hoisting`() {
        val i = eval("""
            var res = f();
            function f() { return 42; }
        """.trimIndent())
        assertEquals(42.0, (i.getValue("res") as JSNumber).value)
    }
    
    @Test
    fun `test block scope let`() {
        // If let is supported as block scoped
        val i = eval("""
            var x = 1;
            {
                let x = 2;
            }
        """.trimIndent())
        // Outer x should remain 1
        assertEquals(1.0, (i.getValue("x") as JSNumber).value)
    }
    
    @Test
    fun `test shadowing`() {
        val i = eval("""
            var x = 10;
            function f() {
                var x = 20;
                return x;
            }
            var res = f();
        """.trimIndent())
        assertEquals(20.0, (i.getValue("res") as JSNumber).value)
        assertEquals(10.0, (i.getValue("x") as JSNumber).value)
    }
}