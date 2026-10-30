package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Promise 组合器、thenable 吸收、微任务顺序与 async/await 时序测试。
 */
class AsyncSemanticsTest {

    @Test
    fun `Promise_allSettled 汇总兑现与拒绝`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = '';
            Promise.allSettled([Promise.resolve(1), Promise.reject('x')])
                .then(function (results) {
                    r = results[0].status + ':' + results[0].value + ':' +
                        results[1].status + ':' + results[1].reason;
                });
            """.trimIndent()
        )
        assertEquals("fulfilled:1:rejected:x", runtime.evalString("r"))
        runtime.eval("var empty = -1; Promise.allSettled([]).then(function (a) { empty = a.length; });")
        assertEquals(0.0, runtime.evalNumber("empty"))

        runtime.eval(
            """
            var keys = '';
            Promise.allSettled([Promise.resolve(1), Promise.reject('x')]).then(function (results) {
                keys = Object.keys(results[0]).join(',') + ':' + Object.keys(results[1]).join(',');
            });
            """.trimIndent()
        )
        assertEquals("status,value:status,reason", runtime.evalString("keys"))
    }

    @Test
    fun `Promise_any 取首个兑现并在全部拒绝时抛 AggregateError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = 0;
            Promise.any([Promise.reject('a'), Promise.resolve(2), Promise.resolve(3)])
                .then(function (value) { r = value; });
            """.trimIndent()
        )
        assertEquals(2.0, runtime.evalNumber("r"))

        runtime.eval(
            """
            var name = '';
            Promise.any([Promise.reject('a'), Promise.reject('b')])
                .catch(function (e) { name = e.name; });
            """.trimIndent()
        )
        assertEquals("AggregateError", runtime.evalString("name"))

        runtime.eval(
            """
            var emptyName = '';
            Promise.any([]).catch(function (e) { emptyName = e.name; });
            """.trimIndent()
        )
        assertEquals("AggregateError", runtime.evalString("emptyName"))
    }

    @Test
    fun `Promise_all 拒绝与普通值混合`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var reason = '';
            Promise.all([Promise.resolve(1), Promise.reject('bad')])
                .then(null, function (e) { reason = e; });
            """.trimIndent()
        )
        assertEquals("bad", runtime.evalString("reason"))

        runtime.eval(
            """
            var joined = '';
            Promise.all([1, Promise.resolve(2), 3]).then(function (values) { joined = values.join(','); });
            """.trimIndent()
        )
        assertEquals("1,2,3", runtime.evalString("joined"))
    }

    @Test
    fun `Promise_all 按输入顺序汇总乱序兑现`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var resolveFirst;
            var resolveSecond;
            var first = new Promise(function (resolve) { resolveFirst = resolve; });
            var second = new Promise(function (resolve) { resolveSecond = resolve; });
            var joined = '';
            Promise.all([first, second]).then(function (values) { joined = values.join(','); });
            resolveSecond(2);
            resolveFirst(1);
            """.trimIndent()
        )
        assertEquals("1,2", runtime.evalString("joined"))
    }

    @Test
    fun `thenable 在 Promise 解析时被吸收`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var resolved = 0;
            Promise.resolve({ then: function (resolve) { resolve(5); } })
                .then(function (value) { resolved = value; });
            """.trimIndent()
        )
        assertEquals(5.0, runtime.evalNumber("resolved"))

        runtime.eval(
            """
            var viaConstructor = 0;
            new Promise(function (resolve) { resolve({ then: function (r) { r(6); } }); })
                .then(function (value) { viaConstructor = value; });
            """.trimIndent()
        )
        assertEquals(6.0, runtime.evalNumber("viaConstructor"))

        runtime.eval(
            """
            var viaAsync = 0;
            async function f() { return await { then: function (resolve) { resolve(7); } }; }
            f().then(function (value) { viaAsync = value; });
            """.trimIndent()
        )
        assertEquals(7.0, runtime.evalNumber("viaAsync"))
    }

    @Test
    fun `then 返回 Promise 时链式展开`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = 0;
            Promise.resolve(1)
                .then(function (value) { return Promise.resolve(value + 1); })
                .then(function (value) { return value + 1; })
                .then(function (value) { r = value; });
            """.trimIndent()
        )
        assertEquals(3.0, runtime.evalNumber("r"))
    }

    @Test
    fun `then 回调抛错转为下游拒绝`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = '';
            Promise.resolve(1)
                .then(function () { throw new Error('inner'); })
                .then(function () { return 'not-reached'; })
                .catch(function (e) { r = e.message; });
            """.trimIndent()
        )
        assertEquals("inner", runtime.evalString("r"))
    }

    @Test
    fun `catch 会透传兑现值并可恢复`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var passThrough = '';
            Promise.resolve('ok')
                .catch(function () { return 'bad'; })
                .then(function (value) { passThrough = value; });
            """.trimIndent()
        )
        assertEquals("ok", runtime.evalString("passThrough"))

        runtime.eval(
            """
            var recovered = '';
            Promise.reject('a')
                .catch(function () { throw 'b'; })
                .catch(function (e) { recovered = e; });
            """.trimIndent()
        )
        assertEquals("b", runtime.evalString("recovered"))
    }

    @Test
    fun `finally 保留兑现值并传播拒绝原因`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var fulfilled = '';
            Promise.resolve('value')
                .finally(function () { return 'ignored'; })
                .then(function (value) { fulfilled = value; });
            """.trimIndent()
        )
        assertEquals("value", runtime.evalString("fulfilled"))

        runtime.eval(
            """
            var rejected = '';
            Promise.reject('reason')
                .finally(function () {})
                .catch(function (e) { rejected = e; });
            """.trimIndent()
        )
        assertEquals("reason", runtime.evalString("rejected"))
    }

    @Test
    fun `Promise 构造器的 resolve 只生效一次`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = 0;
            new Promise(function (resolve, reject) {
                resolve(1);
                resolve(2);
                reject(3);
            }).then(function (value) { r = value; });
            """.trimIndent()
        )
        assertEquals(1.0, runtime.evalNumber("r"))
    }

    @Test
    fun `Promise 以自身解析时抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var resolveSelf;
            var name = '';
            var p = new Promise(function (resolve) { resolveSelf = resolve; });
            p.catch(function (e) { name = e.name; });
            resolveSelf(p);
            """.trimIndent()
        )
        assertEquals("TypeError", runtime.evalString("name"))
    }

    @Test
    fun `Promise_reject 保留原始原因对象`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var reason = { code: 1 };
            var same = false;
            Promise.reject(reason).catch(function (e) { same = (e === reason); });
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("same"))
    }

    @Test
    fun `Promise 回调按微任务顺序执行`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            Promise.resolve()
                .then(function () { log.push('a'); })
                .then(function () { log.push('c'); });
            Promise.resolve().then(function () { log.push('b'); });
            """.trimIndent()
        )
        assertEquals("a,b,c", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `async 函数顺序与 await 让出执行权`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            async function first() {
                log.push(1);
                await 0;
                log.push(3);
            }
            async function second() {
                log.push(2);
            }
            first();
            second();
            """.trimIndent()
        )
        assertEquals("1,2,3", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `await 拒绝也在微任务周期后处理`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            async function f() {
                try {
                    await Promise.reject('x');
                } catch (e) {
                    log.push('c' + e);
                }
            }
            f();
            log.push('s');
            """.trimIndent()
        )
        assertEquals("s,cx", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `async 中 try catch 捕获 await 拒绝`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = '';
            async function f() {
                try {
                    await Promise.reject('bad');
                } catch (e) {
                    r = 'caught:' + e;
                }
            }
            f();
            """.trimIndent()
        )
        assertEquals("caught:bad", runtime.evalString("r"))
    }

    @Test
    fun `async 返回 Promise 会被展开并被 then 接收`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = 0;
            async function f() { return Promise.resolve(7); }
            f().then(function (value) { r = value; });
            """.trimIndent()
        )
        assertEquals(7.0, runtime.evalNumber("r"))
    }

    @Test
    fun `await 在循环中保持顺序`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var r = 0;
            async function f() {
                var sum = 0;
                for (var i = 0; i < 3; i++) {
                    sum += await Promise.resolve(i);
                }
                return sum;
            }
            f().then(function (value) { r = value; });
            """.trimIndent()
        )
        assertEquals(3.0, runtime.evalNumber("r"))
    }

    @Test
    fun `then 与 catch 返回 Promise 实例`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean("Promise.resolve(1).then(function () {}) instanceof Promise")
        )
        assertTrue(runtime.evalBoolean("Promise.reject(1).catch(function () {}) instanceof Promise"))
        assertEquals(true, runtime.evalBoolean("Promise.resolve(1) !== Promise.resolve(1)"))
    }
}
