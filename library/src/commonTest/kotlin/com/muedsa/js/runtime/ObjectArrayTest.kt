package com.muedsa.js.runtime

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ObjectArrayTest {

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
    fun `test object literal`() {
        val i = eval("""
            var o = { x: 1, y: 2 };
            var res = o.x + o.y;
        """.trimIndent())
        assertEquals(3.0, (i.getValue("res") as JSNumber).value)
    }

    @Test
    fun `test array literal`() {
        val i = eval("""
            var arr = [1, 2, 3];
            var len = arr.length;
            var el = arr[1];
        """.trimIndent())
        assertEquals(3.0, (i.getValue("len") as JSNumber).value)
        assertEquals(2.0, (i.getValue("el") as JSNumber).value)
    }

    @Test
    fun `test nested access`() {
        val i = eval("""
            var o = { arr: [{x: 10}] };
            var res = o.arr[0].x;
        """.trimIndent())
        assertEquals(10.0, (i.getValue("res") as JSNumber).value)
    }
    
    @Test
    fun `test array assignment`() {
        val i = eval("""
            var arr = [1];
            arr[1] = 2;
            var len = arr.length;
        """.trimIndent())
        assertEquals(2.0, (i.getValue("len") as JSNumber).value)
        
        val arr = i.getValue("arr")
        assertIs<JSArray>(arr)
        assertEquals(2.0, (arr.getProperty("1") as JSNumber).value)
    }
}