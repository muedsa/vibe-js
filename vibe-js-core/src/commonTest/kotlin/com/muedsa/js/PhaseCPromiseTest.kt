package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阶段 C：Promise 与 async/await。
 */
class PhaseCPromiseTest {

    @Test
    fun `Promise resolve 与 then`() = runTest {
        val runtime = TestRuntime()
        runtime.eval("var r = 0; Promise.resolve(1).then(function (v) { r = v; });")
        assertEquals(1.0, runtime.evalNumber("r"))
    }

    @Test
    fun `Promise 构造器与链式调用`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = 0;
            new Promise(function (resolve) { resolve(3); })
                .then(function (v) { return v + 1; })
                .then(function (v) { r = v; });
            """.trimIndent()
        )
        assertEquals(4.0, runtime.evalNumber("r"))
    }

    @Test
    fun `Promise 拒绝与 catch`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = '';
            Promise.reject(new Error('bad')).catch(function (e) { r = e.message; });
            """.trimIndent()
        )
        assertEquals("bad", runtime.evalString("r"))
    }

    @Test
    fun `Promise 构造器抛出异常`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = '';
            new Promise(function () { throw new Error('boom'); })
                .catch(function (e) { r = e.message; });
            """.trimIndent()
        )
        assertEquals("boom", runtime.evalString("r"))
    }

    @Test
    fun `Promise all 与 race`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = '';
            Promise.all([Promise.resolve(1), 2]).then(function (v) { r = v.join(','); });
            """.trimIndent()
        )
        assertEquals("1,2", runtime.evalString("r"))

        runtime.eval(
            """
            var race = '';
            Promise.race([Promise.resolve('a'), Promise.resolve('b')])
                .then(function (v) { race = v; });
            """.trimIndent()
        )
        assertEquals("a", runtime.evalString("race"))
    }

    @Test
    fun `Promise finally 保留结果`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = '';
            var called = false;
            Promise.resolve('x').finally(function () { called = true; })
                .then(function (v) { r = v + ':' + called; });
            """.trimIndent()
        )
        assertEquals("x:true", runtime.evalString("r"))
    }

    @Test
    fun `async 函数返回 Promise`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = 0;
            async function f() { return 1; }
            f().then(function (v) { r = v; });
            """.trimIndent()
        )
        assertEquals(1.0, runtime.evalNumber("r"))
        assertEquals(
            true,
            runtime.evalBoolean("async function g() { return 1; } g() instanceof Promise")
        )
    }

    @Test
    fun `await 立即值与 Promise`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = 0;
            async function f() { return await 1; }
            f().then(function (v) { r = v; });
            """.trimIndent()
        )
        assertEquals(1.0, runtime.evalNumber("r"))

        runtime.eval(
            """
            var r2 = 0;
            async function g() { return await Promise.resolve(5); }
            g().then(function (v) { r2 = v; });
            """.trimIndent()
        )
        assertEquals(5.0, runtime.evalNumber("r2"))
    }

    @Test
    fun `async 函数抛出异常转为拒绝`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = '';
            async function f() { throw new Error('async-bad'); }
            f().catch(function (e) { r = e.message; });
            """.trimIndent()
        )
        assertEquals("async-bad", runtime.evalString("r"))
    }

    @Test
    fun `async 箭头函数`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = 0;
            var f = async x => x + 1;
            f(1).then(function (v) { r = v; });
            """.trimIndent()
        )
        assertEquals(2.0, runtime.evalNumber("r"))
    }

    @Test
    fun `Promise 的 toStringTag`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "[object Promise]",
            runtime.evalString("Object.prototype.toString.call(Promise.resolve(1))")
        )
        assertTrue(runtime.evalBoolean("Promise.resolve(true) instanceof Promise"))
    }
}
