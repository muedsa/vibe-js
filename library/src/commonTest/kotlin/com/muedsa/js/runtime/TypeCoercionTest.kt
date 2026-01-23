package com.muedsa.js.runtime

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeCoercionTest {

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
    fun `test addition coercion`() {
        // 测试加法运算中的类型转换
        // 规则: 如果任一操作数为字符串，则进行字符串拼接；否则转换为数字相加
        val i = eval("""
            var a = '5' + 3;
            var b = 3 + '5';
            var c = true + 1;
            var d = false + 1;
            var e = null + 1;
            var f = undefined + 1;
        """.trimIndent())

        assertEquals("53", (i.getValue("a") as JSString).value) // 字符串拼接
        assertEquals("35", (i.getValue("b") as JSString).value) // 字符串拼接
        assertEquals(2.0, (i.getValue("c") as JSNumber).value) // true -> 1, 1+1=2
        assertEquals(1.0, (i.getValue("d") as JSNumber).value) // false -> 0, 0+1=1
        assertEquals(1.0, (i.getValue("e") as JSNumber).value) // null -> 0, 0+1=1
        assertTrue((i.getValue("f") as JSNumber).value.isNaN()) // undefined -> NaN
    }

    @Test
    fun `test subtraction coercion`() {
        // 测试减法运算中的类型转换
        // 规则: 操作数总是被转换为数字
        val i = eval("""
            var a = '5' - 2;
            var b = '5' - '2';
            var c = 'abc' - 1;
            var d = null - 1;
            var e = true - 0;
        """.trimIndent())

        assertEquals(3.0, (i.getValue("a") as JSNumber).value) // '5' -> 5
        assertEquals(3.0, (i.getValue("b") as JSNumber).value) // '5' -> 5, '2' -> 2
        assertTrue((i.getValue("c") as JSNumber).value.isNaN()) // 'abc' -> NaN
        assertEquals(-1.0, (i.getValue("d") as JSNumber).value) // null -> 0
        assertEquals(1.0, (i.getValue("e") as JSNumber).value) // true -> 1
    }

    @Test
    fun `test equality coercion`() {
        // 测试宽松相等 (==) 中的类型转换
        // 规则包括: null==undefined, 字符串转数字, 布尔值转数字等
        val i = eval("""
            var a = null == undefined;
            var b = null == 0;
            var c = true == 1;
            var d = false == 0;
            var e = '5' == 5;
            var f = '' == 0;
            var g = '0' == 0;
        """.trimIndent())

        assertEquals(true, (i.getValue("a") as JSBoolean).value) // 特殊规则
        assertEquals(false, (i.getValue("b") as JSBoolean).value) // null 只等于 undefined 或 null
        assertEquals(true, (i.getValue("c") as JSBoolean).value) // true -> 1
        assertEquals(true, (i.getValue("d") as JSBoolean).value) // false -> 0
        assertEquals(true, (i.getValue("e") as JSBoolean).value) // '5' -> 5
        assertEquals(true, (i.getValue("f") as JSBoolean).value) // '' -> 0
        assertEquals(true, (i.getValue("g") as JSBoolean).value) // '0' -> 0
    }

    @Test
    fun `test strict equality no coercion`() {
        // 测试严格相等 (===)
        // 规则: 不进行任何类型转换，类型不同即为 false
        val i = eval("""
            var a = null === undefined;
            var b = '5' === 5;
            var c = true === 1;
        """.trimIndent())

        assertEquals(false, (i.getValue("a") as JSBoolean).value)
        assertEquals(false, (i.getValue("b") as JSBoolean).value)
        assertEquals(false, (i.getValue("c") as JSBoolean).value)
    }
}
