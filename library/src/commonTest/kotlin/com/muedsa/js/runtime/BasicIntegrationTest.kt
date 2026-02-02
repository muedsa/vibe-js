package com.muedsa.js.runtime

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BasicIntegrationTest {

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
    fun `test basic arithmetic`() {
        val i = eval("var a = 10 + 5; var b = 10 - 5; var c = 10 * 5; var d = 10 / 5; var e = 10 % 3;")
        assertEquals(15.0, (i.getValue("a") as JSNumber).value)
        assertEquals(5.0, (i.getValue("b") as JSNumber).value)
        assertEquals(50.0, (i.getValue("c") as JSNumber).value)
        assertEquals(2.0, (i.getValue("d") as JSNumber).value)
        assertEquals(1.0, (i.getValue("e") as JSNumber).value)
    }

    @Test
    fun `test string concatenation`() {
        val i = eval("var s = 'Hello' + ' ' + 'World'; var n = 'Num: ' + 5;")
        assertEquals("Hello World", (i.getValue("s") as JSString).value)
        assertEquals("Num: 5", (i.getValue("n") as JSString).value)
    }

    @Test
    fun `test strict equality`() {
        val i = eval("""
            var a = 5 === 5;
            var b = 5 === '5';
            var c = null === null;
            var d = null === undefined;
        """.trimIndent())

        assertTrue((i.getValue("a") as JSBoolean).value)
        assertFalse((i.getValue("b") as JSBoolean).value)
        assertTrue((i.getValue("c") as JSBoolean).value)
        assertFalse((i.getValue("d") as JSBoolean).value)
    }

    @Test
    fun `test loose equality`() {
        val i = eval("""
            var a = 5 == '5';
            var b = 1 == true;
            var c = null == undefined;
            var d = 0 == null;
        """.trimIndent())

        assertTrue((i.getValue("a") as JSBoolean).value)
        assertTrue((i.getValue("b") as JSBoolean).value)
        assertTrue((i.getValue("c") as JSBoolean).value)
        assertFalse((i.getValue("d") as JSBoolean).value)
    }

    @Test
    fun `test logical operators`() {
        val i = eval("""
            var a = true && false;
            var b = true || false;
            var c = !true;
            var d = !!1;
            var e = 1 && 2; // Returns 2
            var f = 0 || 3; // Returns 3
        """.trimIndent())

        assertFalse((i.getValue("a") as JSBoolean).value)
        assertTrue((i.getValue("b") as JSBoolean).value)
        assertFalse((i.getValue("c") as JSBoolean).value)
        assertTrue((i.getValue("d") as JSBoolean).value)
        assertEquals(2.0, (i.getValue("e") as JSNumber).value)
        assertEquals(3.0, (i.getValue("f") as JSNumber).value)
    }

    @Test
    fun `test bitwise operators`() {
        val i = eval("""
            var a = 5 & 3; // 1
            var b = 5 | 3; // 7
            var c = 5 ^ 3; // 6
            var d = ~5;    // -6
            var e = 5 << 1; // 10
            var f = 5 >> 1; // 2
        """.trimIndent())

        assertEquals(1.0, (i.getValue("a") as JSNumber).value)
        assertEquals(7.0, (i.getValue("b") as JSNumber).value)
        assertEquals(6.0, (i.getValue("c") as JSNumber).value)
        assertEquals(-6.0, (i.getValue("d") as JSNumber).value)
        assertEquals(10.0, (i.getValue("e") as JSNumber).value)
        assertEquals(2.0, (i.getValue("f") as JSNumber).value)
    }

    @Test
    fun `test ternary operator`() {
        val i = eval("var a = true ? 1 : 2; var b = false ? 1 : 2;")
        assertEquals(1.0, (i.getValue("a") as JSNumber).value)
        assertEquals(2.0, (i.getValue("b") as JSNumber).value)
    }

    @Test
    fun `test coalesce operator`() {
        val i = eval("var a = null ?? 1; var b = undefined ?? 2; var c = 0 ?? 3;")
        assertEquals(1.0, (i.getValue("a") as JSNumber).value)
        assertEquals(2.0, (i.getValue("b") as JSNumber).value)
        assertEquals(0.0, (i.getValue("c") as JSNumber).value)
    }
}
