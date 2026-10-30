package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 最小内建对象行为测试（阶段 A 范围）。
 */
class BuiltinsTest {

    @Test
    fun `数组迭代方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "2,4,6",
            runtime.evalString("[1, 2, 3].map(function (x) { return x * 2; }).join(',')")
        )
        assertEquals(
            "2,3",
            runtime.evalString("[1, 2, 3].filter(function (x) { return x > 1; }).join(',')")
        )
        assertEquals(
            6.0,
            runtime.evalNumber("[1, 2, 3].reduce(function (a, b) { return a + b; }, 0)")
        )
        assertEquals(
            false,
            runtime.evalBoolean("[1, 2, 3].every(function (x) { return x > 1; })")
        )
        assertEquals(
            true,
            runtime.evalBoolean("[1, 2, 3].some(function (x) { return x > 2; })")
        )
        assertEquals(
            2.0,
            runtime.evalNumber("[1, 2, 3].find(function (x) { return x > 1; })")
        )
        assertEquals(
            1.0,
            runtime.evalNumber("[1, 2, 3].findIndex(function (x) { return x > 1; })")
        )
        assertEquals(
            3.0,
            runtime.evalNumber("var n = 0; [1, 2, 3].forEach(function () { n++; }); n")
        )
    }

    @Test
    fun `数组增删与排序`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1,9,10", runtime.evalString("[10, 9, 1].sort(function (a, b) { return a - b; }).join(',')"))
        assertEquals("1,10,9", runtime.evalString("[10, 9, 1].sort().join(',')"))
        assertEquals("4,2,3", runtime.evalString("var a = [1, 2, 3]; a.splice(0, 1, 4); a.join(',')"))
        assertEquals("2,3", runtime.evalString("[1, 2, 3].slice(1).join(',')"))
        assertEquals("1,2,3,1,2", runtime.evalString("[1, 2, 3].concat([1, 2]).join(',')"))
        assertEquals("3,2,1", runtime.evalString("[1, 2, 3].reverse().join(',')"))
        assertEquals(3.0, runtime.evalNumber("var a = [1]; a.push(2, 3); a.length"))
        assertEquals(2.0, runtime.evalNumber("var a = [1, 2]; a.pop()"))
        assertEquals(1.0, runtime.evalNumber("var a = [1, 2]; a.shift()"))
        assertEquals(3.0, runtime.evalNumber("var a = [1, 2]; a.unshift(0); a.length"))
        assertEquals(3.0, runtime.evalNumber("[1, 2, 3].at(-1)"))
    }

    @Test
    fun `数组查找`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("[1, 2, 3].indexOf(2)"))
        assertEquals(-1.0, runtime.evalNumber("[1, 2, 3].indexOf(4)"))
        assertEquals(true, runtime.evalBoolean("[1, NaN].includes(NaN)"))
    }

    @Test
    fun `字符串方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals("a+b", runtime.evalString("'a-b'.replace('-', '+')"))
        assertEquals("a+b+c", runtime.evalString("'a-b-c'.replaceAll('-', '+')"))
        assertEquals("a,b", runtime.evalString("'a b'.split(' ').join(',')"))
        assertEquals("  x  ".trim(), runtime.evalString("'  x  '.trim()"))
        assertEquals("abc", runtime.evalString("'ab'.padEnd(3, 'c')"))
        assertEquals("00ab", runtime.evalString("'ab'.padStart(4, '0')"))
        assertEquals("AB", runtime.evalString("'ab'.toUpperCase()"))
        assertEquals(2.0, runtime.evalNumber("'banana'.indexOf('n')"))
        assertEquals(true, runtime.evalBoolean("'banana'.includes('nan')"))
    }

    @Test
    fun `数字方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals("ff", runtime.evalString("(255).toString(16)"))
        assertEquals("1.25", runtime.evalString("(1.25).toFixed(2)"))
        assertEquals("1.4", runtime.evalString("(1.4).toFixed(1)"))
        assertEquals(31.0, runtime.evalNumber("parseInt('0x1f')"))
        assertEquals(3.0, runtime.evalNumber("parseInt('11', 2)"))
    }

    @Test
    fun `Object 静态方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals("a,b", runtime.evalString("Object.keys({ a: 1, b: 2 }).join(',')"))
        assertEquals("1,2", runtime.evalString("Object.values({ a: 1, b: 2 }).join(',')"))
        assertEquals("a=1", runtime.evalString("Object.entries({ a: 1 })[0].join('=')"))
        assertEquals(
            3.0,
            runtime.evalNumber("Object.assign({}, { a: 1 }, { b: 2 }).a + Object.assign({}, { a: 1 }, { b: 2 }).b")
        )
        assertEquals(
            true,
            runtime.evalBoolean("Object.is(NaN, NaN)")
        )
        assertEquals(
            false,
            runtime.evalBoolean("Object.is(0, -0)")
        )
    }

    @Test
    fun `Math 方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("Math.max(1, 2, 3)"))
        assertEquals(-1.0, runtime.evalNumber("Math.round(-1.5)"))
        assertEquals(2.0, runtime.evalNumber("Math.floor(2.9)"))
        assertEquals(3.0, runtime.evalNumber("Math.ceil(2.1)"))
        assertEquals(5.0, runtime.evalNumber("Math.abs(-5)"))
        assertEquals(4.0, runtime.evalNumber("Math.pow(2, 2)"))
        assertEquals(31.0, runtime.evalNumber("Math.clz32(1)"))
        assertEquals(6.0, runtime.evalNumber("Math.imul(2, 3)"))
    }

    @Test
    fun `JSON 嵌套与数组`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "{\"a\":1,\"b\":[1,2],\"c\":\"x\"}",
            runtime.evalString("JSON.stringify({ a: 1, b: [1, 2], c: 'x' })")
        )
        assertEquals(
            2.0,
            runtime.evalNumber("JSON.parse('{\"a\":[1,2]}').a.length")
        )
    }

    @Test
    fun `Error 与 prototypal 继承`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "TypeError: bad",
            runtime.evalString("'' + new TypeError('bad')")
        )
        assertEquals(
            "TypeError",
            runtime.evalString("new TypeError('bad').name")
        )
    }
}
