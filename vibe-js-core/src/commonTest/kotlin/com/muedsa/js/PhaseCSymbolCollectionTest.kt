package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 阶段 C：Symbol、Map/Set、迭代器协议。
 */
class PhaseCSymbolCollectionTest {

    @Test
    fun `Symbol 基础`() = runTest {
        val runtime = TestRuntime()
        assertEquals("symbol", runtime.evalString("typeof Symbol('x')"))
        assertEquals("Symbol(x)", runtime.evalString("Symbol('x').toString()"))
        assertEquals(false, runtime.evalBoolean("Symbol('a') === Symbol('a')"))
        assertEquals(true, runtime.evalBoolean("Symbol.for('a') === Symbol.for('a')"))
        assertEquals("a", runtime.evalString("Symbol.keyFor(Symbol.for('a'))"))
        assertEquals("Symbol(x)", runtime.evalString("String(Symbol('x'))"))
        assertEquals("Symbol(Symbol.iterator)", runtime.evalString("Symbol.iterator.toString()"))
    }

    @Test
    fun `Symbol 作为属性键`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("var s = Symbol('k'); var o = {}; o[s] = 1; o[s]"))
        assertEquals(0.0, runtime.evalNumber("var s = Symbol('k'); var o = {}; o[s] = 1; Object.keys(o).length"))
        assertEquals(
            1.0,
            runtime.evalNumber(
                "var s = Symbol('k'); var o = {}; o[s] = 1; Object.getOwnPropertySymbols(o).length"
            )
        )
    }

    @Test
    fun `Map 基础与 size`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            1.0,
            runtime.evalNumber("var m = new Map(); m.set('a', 1); m.get('a')")
        )
        assertEquals(1.0, runtime.evalNumber("var m = new Map(); m.set('a', 1); m.size"))
        assertEquals(true, runtime.evalBoolean("var m = new Map(); m.set('a', 1); m.has('a')"))
        assertEquals(
            true,
            runtime.evalBoolean("var m = new Map(); m.set('a', 1); m.delete('a')")
        )
        assertEquals(0.0, runtime.evalNumber("var m = new Map(); m.set('a', 1); m.delete('a'); m.size"))
        assertEquals(2.0, runtime.evalNumber("new Map([['a', 1], ['b', 2]]).size"))
        assertEquals(1.0, runtime.evalNumber("var m = new Map(); m.set(NaN, 1); m.get(NaN)"))
    }

    @Test
    fun `Map 迭代与 for-of`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "a,b",
            runtime.evalString("var m = new Map([['a', 1], ['b', 2]]); [...m.keys()].join(',')")
        )
        assertEquals(
            "1,2",
            runtime.evalString("var m = new Map([['a', 1], ['b', 2]]); [...m.values()].join(',')")
        )
        assertEquals(
            "a1b2",
            runtime.evalString(
                "var s = ''; for (var [k, v] of new Map([['a', 1], ['b', 2]])) { s += k + v; } s"
            )
        )
        assertEquals(
            "[object Map]",
            runtime.evalString("Object.prototype.toString.call(new Map())")
        )
    }

    @Test
    fun `Set 基础与迭代`() = runTest {
        val runtime = TestRuntime()
        assertEquals(2.0, runtime.evalNumber("new Set([1, 2, 2]).size"))
        assertEquals(1.0, runtime.evalNumber("new Set([1, 2, 2]).values().next().value"))
        assertEquals("1,2", runtime.evalString("[...new Set([1, 2, 2])].join(',')"))
        assertEquals(
            "1,2",
            runtime.evalString("var s = new Set(); s.add(1).add(2); [...s].join(',')")
        )
        assertEquals("[object Set]", runtime.evalString("Object.prototype.toString.call(new Set())"))
    }

    @Test
    fun `WeakMap 与 WeakSet`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            1.0,
            runtime.evalNumber("var w = new WeakMap(); var k = {}; w.set(k, 1); w.get(k)")
        )
        assertEquals(
            true,
            runtime.evalBoolean("var w = new WeakMap(); var k = {}; w.set(k, 1); w.has(k)")
        )
        assertEquals(
            true,
            runtime.evalBoolean("var w = new WeakMap(); var k = {}; w.set(k, 1); w.delete(k)")
        )
        assertEquals(
            true,
            runtime.evalBoolean("var w = new WeakSet(); var k = {}; w.add(k); w.has(k)")
        )
    }

    @Test
    fun `数组与字符串迭代器`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1,2", runtime.evalString("[...[1, 2]].join(',')"))
        assertEquals(
            "0,10",
            runtime.evalString("var it = [10, 20].entries(); it.next().value.join(',')")
        )
        assertEquals(
            0.0,
            runtime.evalNumber("var it = [10, 20].keys(); it.next().value")
        )
        assertEquals(
            2.0,
            runtime.evalNumber("Array.from(new Map([['a', 1], ['b', 2]])).length")
        )
    }
}
