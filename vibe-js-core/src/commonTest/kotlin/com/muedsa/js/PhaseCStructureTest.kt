package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 阶段 C：解构、展开与 for-of。
 */
class PhaseCStructureTest {

    @Test
    fun `数组解构声明`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("var [a, b] = [1, 2]; a + b"))
        assertEquals(3.0, runtime.evalNumber("var [a = 1, b = 2] = [undefined]; a + b"))
        assertEquals(
            "2,3",
            runtime.evalString("var [a, ...rest] = [1, 2, 3]; rest.join(',')")
        )
        assertEquals(3.0, runtime.evalNumber("var [[a], [b]] = [[1], [2]]; a + b"))
        assertEquals(1.0, runtime.evalNumber("var [a] = [1, 2, 3]; a"))
    }

    @Test
    fun `对象解构声明`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("var { a, b } = { a: 1, b: 2 }; a + b"))
        assertEquals(5.0, runtime.evalNumber("var { a: x } = { a: 5 }; x"))
        assertEquals(3.0, runtime.evalNumber("var { a = 3 } = {}; a"))
        assertEquals(2.0, runtime.evalNumber("var { a, ...rest } = { a: 1, b: 2 }; rest.b"))
        assertEquals(7.0, runtime.evalNumber("var k = 'a'; var { [k]: v } = { a: 7 }; v"))
        assertEquals(
            3.0,
            runtime.evalNumber("var { a: { b } } = { a: { b: 3 } }; b")
        )
    }

    @Test
    fun `解构赋值`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("var a, b; [a, b] = [1, 2]; a + b"))
        assertEquals(9.0, runtime.evalNumber("var o = {}; ({ x: o.x } = { x: 9 }); o.x"))
        assertEquals(2.0, runtime.evalNumber("var a; ({ a } = { a: 2 }); a"))
        assertEquals(3.0, runtime.evalNumber("var o = {}; [o.a, o.b] = [1, 2]; o.a + o.b"))
    }

    @Test
    fun `数组与调用展开`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1,2,3", runtime.evalString("[...[1, 2], 3].join(',')"))
        assertEquals("a,b", runtime.evalString("[...'ab'].join(',')"))
        assertEquals(
            6.0,
            runtime.evalNumber("function f(a, b, c) { return a + b + c; } f(...[1, 2], 3)")
        )
        assertEquals(
            "1,2,3",
            runtime.evalString("var source = [1, 2, 3]; source.push(...[4]); source.slice(0, 3).join(',')")
        )
    }

    @Test
    fun `对象展开`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("var o = { ...{ a: 1 }, b: 2 }; o.a + o.b"))
        assertEquals(2.0, runtime.evalNumber("var o = { ...{ a: 1 }, a: 2 }; o.a"))
        assertEquals(1.0, runtime.evalNumber("var o = { ...null, ...undefined, a: 1 }; o.a"))
    }

    @Test
    fun `for-of 遍历与闭包`() = runTest {
        val runtime = TestRuntime()
        assertEquals(6.0, runtime.evalNumber("var s = 0; for (var v of [1, 2, 3]) { s += v; } s"))
        assertEquals("ab", runtime.evalString("var s = ''; for (var c of 'ab') { s += c; } s"))
        assertEquals(
            "1,2",
            runtime.evalString(
                "var fs = []; for (let v of [1, 2]) { fs.push(() => v); } fs[0]() + ',' + fs[1]()"
            )
        )
        assertEquals(
            "37",
            runtime.evalString(
                "var s = ''; for (var [a, b] of [[1, 2], [3, 4]]) { s += a + b; } s"
            )
        )
        assertEquals(5.0, runtime.evalNumber("var a; for (a of [5]) { } a"))
    }

    @Test
    fun `for-of 的 break 与标签`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            6.0,
            runtime.evalNumber("var s = 0; for (var v of [1, 2, 3, 4]) { if (v === 4) { break; } s += v; } s")
        )
        assertEquals(
            5.0,
            runtime.evalNumber(
                "var s = 0; outer: for (var a of [1, 2]) { for (var b of [1, 2]) { if (a === 2) { break outer; } s += a + b; } } s"
            )
        )
    }
}
