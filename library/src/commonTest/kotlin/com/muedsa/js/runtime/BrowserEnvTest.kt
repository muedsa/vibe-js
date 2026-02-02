package com.muedsa.js.runtime

import com.muedsa.js.createBrowserRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.value.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BrowserEnvTest {

    private fun eval(code: String): Interpreter {
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = createBrowserRuntime()
        interpreter.interpret(program)
        return interpreter
    }

    @Test
    fun `test window object`() {
        val i = eval("""
            var w = window;
            var isWin = w === window;
        """.trimIndent())
        assertEquals(true, (i.getValue("isWin") as JSBoolean).value)
        assertIs<JSObject>(i.getValue("w"))
    }

    @Test
    fun `test atob and btoa`() {
        // "Hello" -> "SGVsbG8="
        val i = eval("""
            var encoded = btoa("Hello");
            var decoded = atob(encoded);
        """.trimIndent())

        assertEquals("SGVsbG8=", (i.getValue("encoded") as JSString).value)
        assertEquals("Hello", (i.getValue("decoded") as JSString).value)
    }

    @Test
    fun `test JSON stringify`() {
        val i = eval("""
            var obj = { x: 1, y: "a" };
            var json = JSON.stringify(obj);
        """.trimIndent())

        // JSON string order is not guaranteed in spec but usually insertion order or alphabetical
        // Simple assertion to check it contains keys/values
        val json = (i.getValue("json") as JSString).value
        assertTrue(json.contains("\"x\":1"))
        assertTrue(json.contains("\"y\":\"a\""))
        assertTrue(json.startsWith("{") && json.endsWith("}"))
    }

    @Test
    fun `test JSON parse`() {
        val i = eval("""
            var json = '{"x": 10, "y": "hello"}';
            var obj = JSON.parse(json);
            var x = obj.x;
            var y = obj.y;
        """.trimIndent())

        assertEquals(10.0, (i.getValue("x") as JSNumber).value)
        assertEquals("hello", (i.getValue("y") as JSString).value)
    }

    @Test
    fun `test console log`() {
        // console.log returns undefined
        val i = eval("""
            var res = console.log("Test");
        """.trimIndent())
        assertEquals(JSUndefined, i.getValue("res"))
    }

    @Test
    fun `test number functions on window`() {
        // parseInt, parseFloat, isNaN, isFinite should be on window/global
        val i = eval("""
            var a = parseInt("123");
            var b = isNaN(NaN);
            var c = isFinite(100);
        """.trimIndent())

        assertEquals(123.0, (i.getValue("a") as JSNumber).value)
        assertEquals(true, (i.getValue("b") as JSBoolean).value)
        assertEquals(true, (i.getValue("c") as JSBoolean).value)
    }
}
