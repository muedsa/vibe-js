package com.muedsa.js.runtime.internal

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSNumber
import kotlin.math.E
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertTrue

class JSMathTest {

    private fun eval(code: String): Interpreter {
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = createRuntime()
        interpreter.interpret(program)
        return interpreter
    }

    private fun assertNumberEquals(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        if (expected.isNaN() && actual.isNaN()) return
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "Expected $expected but got $actual")
    }

    @Test
    fun `test Math constants`() {
        val i = eval("""
            var pi = Math.PI;
            var e = Math.E;
            var ln2 = Math.LN2;
            var sqrt2 = Math.SQRT2;
        """.trimIndent())
        
        assertNumberEquals(PI, (i.getValue("pi") as JSNumber).value)
        assertNumberEquals(E, (i.getValue("e") as JSNumber).value)
        assertNumberEquals(kotlin.math.ln(2.0), (i.getValue("ln2") as JSNumber).value)
        assertNumberEquals(kotlin.math.sqrt(2.0), (i.getValue("sqrt2") as JSNumber).value)
    }

    @Test
    fun `test Math basic functions`() {
        val i = eval("""
            var absVal = Math.abs(-10.5);
            var ceilVal = Math.ceil(1.1);
            var floorVal = Math.floor(1.9);
            var roundVal = Math.round(1.5);
            var maxVal = Math.max(1, 5, 2);
            var minVal = Math.min(1, 5, -1);
            var powVal = Math.pow(2, 3);
            var sqrtVal = Math.sqrt(16);
        """.trimIndent())

        assertNumberEquals(10.5, (i.getValue("absVal") as JSNumber).value)
        assertNumberEquals(2.0, (i.getValue("ceilVal") as JSNumber).value)
        assertNumberEquals(1.0, (i.getValue("floorVal") as JSNumber).value)
        assertNumberEquals(2.0, (i.getValue("roundVal") as JSNumber).value)
        assertNumberEquals(5.0, (i.getValue("maxVal") as JSNumber).value)
        assertNumberEquals(-1.0, (i.getValue("minVal") as JSNumber).value)
        assertNumberEquals(8.0, (i.getValue("powVal") as JSNumber).value)
        assertNumberEquals(4.0, (i.getValue("sqrtVal") as JSNumber).value)
    }

    @Test
    fun `test Math trig functions`() {
        val i = eval("""
            var sinVal = Math.sin(Math.PI / 2);
            var cosVal = Math.cos(Math.PI);
            var tanVal = Math.tan(0);
        """.trimIndent())

        assertNumberEquals(1.0, (i.getValue("sinVal") as JSNumber).value)
        assertNumberEquals(-1.0, (i.getValue("cosVal") as JSNumber).value)
        assertNumberEquals(0.0, (i.getValue("tanVal") as JSNumber).value)
    }

    @Test
    fun `test Math random`() {
        val i = eval("var r = Math.random();")
        val r = (i.getValue("r") as JSNumber).value
        assertTrue(r >= 0.0 && r < 1.0)
    }

    @Test
    fun `test Math imul`() {
        val i = eval("var val = Math.imul(0xffffffff, 5);")
        // 0xffffffff is -1 as int32
        // -1 * 5 = -5
        assertNumberEquals(-5.0, (i.getValue("val") as JSNumber).value)
    }
    
    @Test
    fun `test Math hypot`() {
         val i = eval("var h = Math.hypot(3, 4);")
         assertNumberEquals(5.0, (i.getValue("h") as JSNumber).value)
    }
}
