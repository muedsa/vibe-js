package com.muedsa.js.runtime

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.value.JSString
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeofTest {

    private fun eval(code: String): Interpreter {
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = createRuntime()
        interpreter.interpret(program)
        return interpreter
    }

    private fun assertTypeof(code: String, expectedType: String) {
        val interpreter = eval("var result = $code;")
        val result = interpreter.getValue("result")
        assertEquals(expectedType, (result as JSString).value, "Failed for: $code")
    }

    @Test
    fun `test typeof basic types`() {
        assertTypeof("typeof 123", "number")
        assertTypeof("typeof NaN", "number")
        assertTypeof("typeof Infinity", "number")
        assertTypeof("typeof 'hello'", "string")
        assertTypeof("typeof true", "boolean")
        assertTypeof("typeof false", "boolean")
        assertTypeof("typeof undefined", "undefined")
    }

    @Test
    fun `test typeof objects and null`() {
        assertTypeof("typeof null", "object")
        assertTypeof("typeof {}", "object")
        assertTypeof("typeof []", "object")
        assertTypeof("typeof new Array()", "object")
    }

    @Test
    fun `test typeof function`() {
        assertTypeof("typeof function(){}", "function")
        // assertTypeof("typeof (() => {})", "function") // Arrow functions not supported yet
        // assertTypeof("typeof console.log", "function") // console.log not in base runtime
        
        // Let's use a defined function
        val interpreter = eval("""
            function f() {}
            var t = typeof f;
        """.trimIndent())
        assertEquals("function", (interpreter.getValue("t") as JSString).value)
    }

    @Test
    fun `test typeof undeclared variable`() {
        // This is a special safety check for typeof
        assertTypeof("typeof undeclaredVariableThatDoesNotExist", "undefined")
    }

    @Test
    fun `test typeof expressions`() {
        assertTypeof("typeof (1 + 2)", "number")
        assertTypeof("typeof (1 + '2')", "string")
        assertTypeof("typeof (typeof 1)", "string")
        assertTypeof("typeof typeof typeof 1", "string")
    }
    
    @Test
    fun `test typeof operator precedence`() {
        // typeof 1 + 2 -> "number2" because typeof binds tighter than +?
        // Wait, typeof is a unary operator.
        // typeof 1 + 2 parses as (typeof 1) + 2 -> "number" + 2 -> "number2"
        val interpreter = eval("var res = typeof 1 + 2;")
        assertEquals("number2", (interpreter.getValue("res") as JSString).value)
        
        // typeof (1 + 2) -> "number"
        val interpreter2 = eval("var res = typeof(1 + 2);")
        assertEquals("number", (interpreter2.getValue("res") as JSString).value)
    }
}
