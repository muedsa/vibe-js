package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ES2015 收尾能力：参数/ catch 解构、Object 批量定义、Symbol.hasInstance。
 */
class PhaseCFinalTest {

    @Test
    fun `参数解构`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            6.0,
            runtime.evalNumber("function f({ a, b }, [c]) { return a + b + c; } f({ a: 1, b: 2 }, [3])")
        )
        assertEquals(
            5.0,
            runtime.evalNumber("function f({ a = 5 } = {}) { return a; } f()")
        )
        assertEquals(
            7.0,
            runtime.evalNumber("var f = ({ a }) => a; f({ a: 7 })")
        )
        assertEquals(
            3.0,
            runtime.evalNumber("var f = ({ a }, ...rest) => a + rest.length; f({ a: 1 }, 2, 3)")
        )
    }

    @Test
    fun `catch 解构`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "x",
            runtime.evalString(
                "var r = ''; try { throw { message: 'x' }; } catch ({ message }) { r = message; } r"
            )
        )
        assertEquals(
            3.0,
            runtime.evalNumber(
                "var r = 0; try { throw [1, 2]; } catch ([a, b]) { r = a + b; } r"
            )
        )
    }

    @Test
    fun `Object create 属性与 defineProperties`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            2.0,
            runtime.evalNumber(
                "Object.create({ z: 1 }, { a: { value: 2, enumerable: true } }).a"
            )
        )
        assertEquals(
            3.0,
            runtime.evalNumber(
                """
                var o = {};
                Object.defineProperties(o, {
                    a: { value: 1, enumerable: true },
                    b: { get: function () { return 2; } }
                });
                o.a + o.b;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `Symbol hasInstance`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "true:false",
            runtime.evalString(
                """
                class Even {
                    static [Symbol.hasInstance](value) { return value % 2 === 0; }
                }
                (2 instanceof Even) + ':' + (3 instanceof Even);
                """.trimIndent()
            )
        )
    }

    @Test
    fun `locale 别名`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1,2", runtime.evalString("[1, 2].toLocaleString()"))
        assertEquals("AB", runtime.evalString("'ab'.toLocaleUpperCase()"))
        assertEquals("[object Object]", runtime.evalString("Object.prototype.toLocaleString.call({})"))
    }
}
