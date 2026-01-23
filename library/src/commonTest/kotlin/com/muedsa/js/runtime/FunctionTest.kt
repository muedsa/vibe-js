package com.muedsa.js.runtime

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.value.JSNumber
import kotlin.test.Test
import kotlin.test.assertEquals

class FunctionTest {

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
    fun `test basic function`() {
        val i = eval("""
            function add(a, b) {
                return a + b;
            }
            var res = add(10, 20);
        """.trimIndent())
        assertEquals(30.0, (i.getValue("res") as JSNumber).value)
    }

    @Test
    fun `test recursion`() {
        val i = eval("""
            function fib(n) {
                if (n <= 1) return n;
                return fib(n-1) + fib(n-2);
            }
            var res = fib(6); // 8
        """.trimIndent())
        assertEquals(8.0, (i.getValue("res") as JSNumber).value)
    }

    @Test
    fun `test closure`() {
        val i = eval("""
            function makeAdder(x) {
                return function(y) {
                    return x + y;
                };
            }
            var add5 = makeAdder(5);
            var res = add5(2);
        """.trimIndent())
        assertEquals(7.0, (i.getValue("res") as JSNumber).value)
    }
    
    @Test
    fun `test IIFE`() {
        val i = eval("""
            var f = function(x) { return x * x; };
            var res = f(4);
        """.trimIndent())
        assertEquals(16.0, (i.getValue("res") as JSNumber).value)
    }
    
    @Test
    fun `test function scope`() {
        val i = eval("""
            var x = "global";
            function test() {
                var x = "local";
                return x;
            }
            var res = test();
        """.trimIndent())
        assertEquals("local", i.getValue("res").toPrimitiveString())
        assertEquals("global", i.getValue("x").toPrimitiveString())
    }
}