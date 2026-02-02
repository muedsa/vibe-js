package com.muedsa.js.runtime

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundaryTest {

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
    fun `test division by zero`() {
        val i = eval("""
            var a = 1 / 0;
            var b = -1 / 0;
            var c = 0 / 0;
        """.trimIndent())

        val a = (i.getValue("a") as JSNumber).value
        assertEquals(Double.POSITIVE_INFINITY, a)

        val b = (i.getValue("b") as JSNumber).value
        assertEquals(Double.NEGATIVE_INFINITY, b)

        val c = (i.getValue("c") as JSNumber).value
        assertTrue(c.isNaN())
    }

    @Test
    fun `test type coercion`() {
        val i = eval("""
            var a = 1 + "2";
            var b = "3" + 4;
            var c = true + 1;
            var d = null + 1;
            var e = undefined + 1; // undefined -> NaN, NaN + 1 -> NaN
        """.trimIndent())

        assertEquals("12", (i.getValue("a") as JSString).value)
        assertEquals("34", (i.getValue("b") as JSString).value)
        assertEquals(2.0, (i.getValue("c") as JSNumber).value)
        assertEquals(1.0, (i.getValue("d") as JSNumber).value)
        assertTrue((i.getValue("e") as JSNumber).value.isNaN())
    }

    @Test
    fun `test undefined variable access`() {
        // Accessing undefined variable might return JSUndefined or throw ReferenceError depending on implementation.
        // Interpreter.kt: is Identifier -> currentEnv.get(expression.name)
        // Environment.kt likely handles 'get'. If not found, does it throw?
        // Let's assume strict mode or standard JS behavior where 'x' throws if not defined.
        // Wait, Interpreter.kt `evaluate` -> `currentEnv.get`.
        // I need to check `Environment.kt` to be sure.
        // But usually it throws ReferenceError.

        assertFailsWith<JSException> {
            eval("var x = y;") // y is undefined
        }
    }

    @Test
    fun `test variable shadowing`() {
        val i = eval("""
            var x = "global";
            function test() {
                var x = "local";
                return x;
            }
            var res = test();
        """.trimIndent())

        assertEquals("local", (i.getValue("res") as JSString).value)
        assertEquals("global", (i.getValue("x") as JSString).value)
    }

    @Test
    fun `test short circuit evaluation`() {
        val i = eval("""
            var x = 1;
            true || (x = 2); // Should not execute right side
            var y = 1;
            false && (y = 2); // Should not execute right side
        """.trimIndent())

        assertEquals(1.0, (i.getValue("x") as JSNumber).value)
        assertEquals(1.0, (i.getValue("y") as JSNumber).value)
    }

    @Test
    fun `test property access on null undefined`() {
        assertFailsWith<JSException> {
            eval("null.prop")
        }
        assertFailsWith<JSException> {
            eval("undefined.prop")
        }
    }
}
