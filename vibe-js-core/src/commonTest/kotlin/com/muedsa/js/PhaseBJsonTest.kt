package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JSON 选项、Array 新增方法与 Symbol.toPrimitive 回归。
 */
class PhaseBJsonTest {

    @Test
    fun `JSON stringify space`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "{\n  \"a\": 1\n}",
            runtime.evalString("JSON.stringify({ a: 1 }, null, 2)")
        )
        assertEquals(
            "[1,2]",
            runtime.evalString("JSON.stringify([1, 2], null, 0)")
        )
    }

    @Test
    fun `JSON stringify replacer 数组与函数`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "{\"a\":1}",
            runtime.evalString("JSON.stringify({ a: 1, b: 2 }, ['a'])")
        )
        assertEquals(
            "{\"a\":2}",
            runtime.evalString(
                "JSON.stringify({ a: 1 }, function (k, v) { return typeof v === 'number' ? v * 2 : v; })"
            )
        )
    }

    @Test
    fun `JSON stringify toJSON`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "{\"b\":2}",
            runtime.evalString(
                "JSON.stringify({ a: 1, toJSON: function () { return { b: 2 }; } })"
            )
        )
    }

    @Test
    fun `JSON parse reviver`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            2.0,
            runtime.evalNumber(
                "JSON.parse('{\"a\":1}', function (k, v) { return typeof v === 'number' ? v + 1 : v; }).a"
            )
        )
        assertEquals(
            "1,2",
            runtime.evalString(
                "JSON.parse('[1,2]', function (k, v) { return typeof v === 'number' ? v : v; }).join(',')"
            )
        )
    }

    @Test
    fun `Array fill 与 copyWithin`() = runTest {
        val runtime = TestRuntime()
        assertEquals("0,0,0", runtime.evalString("[1, 2, 3].fill(0).join(',')"))
        assertEquals("1,0,0,4", runtime.evalString("[1, 2, 3, 4].fill(0, 1, 3).join(',')"))
        assertEquals("3,4,3,4", runtime.evalString("[1, 2, 3, 4].copyWithin(0, 2).join(',')"))
    }

    @Test
    fun `Symbol toPrimitive`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "s",
            runtime.evalString(
                "var o = {}; o[Symbol.toPrimitive] = function (hint) { return hint === 'string' ? 's' : 1; }; String(o)"
            )
        )
        assertEquals(
            1.0,
            runtime.evalNumber(
                "var o = {}; o[Symbol.toPrimitive] = function (hint) { return hint === 'string' ? 's' : 1; }; +o"
            )
        )
    }
}
