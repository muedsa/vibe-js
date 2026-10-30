package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 阶段 C：箭头函数、默认参数、rest 参数。
 */
class PhaseCParameterTest {

    @Test
    fun `箭头函数表达式体与块体`() = runTest {
        val runtime = TestRuntime()
        assertEquals(2.0, runtime.evalNumber("var f = (x) => x + 1; f(1)"))
        assertEquals(6.0, runtime.evalNumber("var f = x => x * 2; f(3)"))
        assertEquals(42.0, runtime.evalNumber("var f = () => 42; f()"))
        assertEquals(3.0, runtime.evalNumber("var f = x => { return x + 1; }; f(2)"))
    }

    @Test
    fun `默认参数与函数 length`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("function f(a, b = 2) { return a + b; } f(1)"))
        assertEquals(6.0, runtime.evalNumber("function f(a, b = 2) { return a + b; } f(1, 5)"))
        assertEquals(2.0, runtime.evalNumber("function f(a, b = a + 1) { return b; } f(1)"))
        assertEquals(1.0, runtime.evalNumber("function f(a, b = 1, c) {}; f.length"))
    }

    @Test
    fun `rest 参数`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "1:2:3",
            runtime.evalString(
                "function f(a, ...rest) { return a + ':' + rest.length + ':' + rest[1]; } f(1, 2, 3)"
            )
        )
        assertEquals(0.0, runtime.evalNumber("function f(...rest) { return rest.length; } f()"))
    }

    @Test
    fun `箭头函数的词法 this`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            2.0,
            runtime.evalNumber(
                "var o = { x: 2, f: function () { var g = () => this.x; return g(); } }; o.f()"
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean(
                "function f() { 'use strict'; var g = () => this; return g() === undefined; } f()"
            )
        )
    }

    @Test
    fun `箭头函数继承 arguments`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            2.0,
            runtime.evalNumber("function f() { var g = () => arguments.length; return g(); } f(1, 2)")
        )
    }

    @Test
    fun `箭头函数不能作为构造器`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> { runtime.eval("var f = () => 1; new f()") }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `默认参数在调用作用域求值`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            5.0,
            runtime.evalNumber("var x = 5; function f(a = x) { return a; } f()")
        )
        assertEquals(
            5.0,
            runtime.evalNumber("var x = 5; function f(a = x) { var x = 2; return a; } f()")
        )
    }
}
