package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 严格模式与非严格模式的关键差异。
 */
class StrictModeTest {

    @Test
    fun `严格模式禁止隐式全局变量`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> {
            runtime.eval("\"use strict\"; undeclared = 1;")
        }
        assertEquals("ReferenceError", (error.value as JSError).errorName)
    }

    @Test
    fun `非严格模式创建隐式全局变量`() = runTest {
        val runtime = TestRuntime()
        runtime.eval("undeclared = 1;")
        assertEquals(1.0, runtime.evalNumber("globalThis.undeclared"))
    }

    @Test
    fun `严格函数中的 this 为 undefined`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean(
                "function f() { 'use strict'; return this; } f() === undefined"
            )
        )
    }

    @Test
    fun `非严格函数中的 this 为全局对象`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean("function f() { return this; } f() === globalThis")
        )
    }

    @Test
    fun `严格模式从外层函数继承到嵌套函数`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean(
                "function f() { 'use strict'; return function () { return this; }; } f()() === undefined"
            )
        )
    }

    @Test
    fun `严格模式下的只读属性赋值抛错`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> {
            runtime.eval(
                """
                "use strict";
                var o = {};
                Object.defineProperty(o, "x", { value: 1, writable: false });
                o.x = 2;
                """.trimIndent()
            )
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `const 重新赋值抛错`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> {
            runtime.eval("\"use strict\"; const a = 1; a = 2;")
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `严格模式下对象方法调用的 this 仍然是对象`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            2.0,
            runtime.evalNumber(
                "var o = { x: 2, f: function () { 'use strict'; return this.x; } }; o.f();"
            )
        )
    }
}
