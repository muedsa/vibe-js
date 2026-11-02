package com.muedsa.js

import com.muedsa.js.runtime.DefaultHostEnvironment
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * 宿主取消契约的跨平台回归：主动发出取消，不依赖真实线程或平台定时器。
 *
 * 长任务使用有限上界；即使取消检查退化，测试也会通过状态断言失败，
 * 不会在 JS/Wasm 的单线程测试进程内永久卡死。真实死循环超时另在 JVM 验证。
 */
class RuntimeCancellationTest {

    @Test
    fun `各类循环在固定检查点观察取消且不会执行到末尾`() = runTest {
        val loops = listOf(
            "while (i < 10000) { i++; }",
            "do { i++; } while (i < 10000);",
            "for (; i < 10000; i++) {}",
            "for (let key in items) { i++; }",
            "for (let value of items) { i++; }",
            // 严格模式尾调用不增长宿主调用栈，也需要响应取消。
            "function next(n) { 'use strict'; if (n === 0) return; return next(n - 1); } next(10000);",
        )
        for (loop in loops) {
            val runtime = TestRuntime()
            runtime.eval("var items = Array.from({length: 10000}, function (_, i) { return i; });")
            assertCancelledBeforeEnd(runtime, "var i = 0; $loop")
        }
    }

    @Test
    fun `没有循环的语句列表和表达式也能观察取消`() = runTest {
        // 分别覆盖大量空语句与单个逗号表达式，避免仅在循环上加检查。
        assertCancelledBeforeEnd(TestRuntime(), ";".repeat(4096))
        assertCancelledBeforeEnd(TestRuntime(), List(4096) { "1" }.joinToString(",") + ";")
    }

    @Test
    fun `宿主取消原样传播且恢复词法环境调用栈和运行时锁`() = runTest {
        val runtime = TestRuntime()
        val cancellation = CancellationException("宿主中止本次脚本")
        runtime.engine.registry.defineGlobal("stop", JSNativeFunction("stop") { _, _, _ ->
            throw cancellation
        })
        val actual = assertFailsWith<CancellationException> {
            runtime.eval(
                """
                var kept = 1;
                var caught = false;
                var finalized = false;
                function work() {
                    'use strict';
                    for (let local = 0; local < 1; local++) {
                        using resource = { [Symbol.dispose]: function () { kept = 99; } };
                        try { kept = 2; stop(); }
                        catch (e) { caught = true; }
                        finally { finalized = true; }
                    }
                }
                work();
                """.trimIndent()
            )
        }
        assertSame(cancellation, actual)
        // 宿主取消直接退出，不进入 JS catch/finally 或用户资源释放器，已写入值保留。
        assertEquals("2:false:false:undefined", runtime.evalString(
            "kept + ':' + caught + ':' + finalized + ':' + typeof local"
        ))
        // 后续脚本恢复 sloppy 环境，且 using 作用域及尾调用阻塞状态没有残留。
        assertEquals(42.0, runtime.evalNumber(
            """
            function next(n) { 'use strict'; return n ? next(n - 1) : 42; }
            { using fresh = { [Symbol.dispose]: function () { kept++; } }; }
            reusableImplicit = next(2000);
            reusableImplicit;
            """.trimIndent()
        ))
        assertEquals(3.0, runtime.evalNumber("kept"))
        val error = assertFailsWith<JSException> { runtime.eval("missingAfterCancellation") }
        assertEquals(listOf("<global>"), error.stack.map { it.functionName })
    }

    @Test
    fun `取消时丢弃剩余微任务且不会覆盖原始取消异常`() = runTest {
        val runtime = TestRuntime()
        val cancellation = CancellationException("保留原始取消对象")
        runtime.engine.registry.defineGlobal("stop", JSNativeFunction("stop") { _, _, _ ->
            throw cancellation
        })
        assertSame(cancellation, assertFailsWith<CancellationException> {
            runtime.eval(
                """
                var stale = false;
                Promise.resolve().then(function () { stale = true; throw new Error('stale'); });
                stop();
                """.trimIndent()
            )
        })
        assertEquals(false, runtime.evalBoolean("stale"))
        runtime.eval("Promise.resolve().then(function () { stale = true; });")
        assertEquals(true, runtime.evalBoolean("stale"))
    }

    @Test
    fun `微任务阶段取消也原样传播并清除尚未执行的回调`() = runTest {
        val runtime = TestRuntime()
        val cancellation = CancellationException("中止微任务")
        runtime.engine.registry.defineGlobal("stop", JSNativeFunction("stop") { _, _, _ ->
            throw cancellation
        })
        assertSame(cancellation, assertFailsWith<CancellationException> {
            runtime.eval(
                """
                var stale = false;
                Promise.resolve().then(function () { stop(); });
                Promise.resolve().then(function () { stale = true; });
                """.trimIndent()
            )
        })
        assertEquals(false, runtime.evalBoolean("stale"))
    }

    @Test
    fun `不经过解释器的原生微任务重入也会观察取消`() = runTest {
        val runtime = TestRuntime()
        val job = Job()
        var calls = 0
        lateinit var task: suspend () -> Unit
        task = {
            calls++
            job.cancel()
            if (calls < 10000) runtime.engine.eventLoop.enqueueMicrotask(task)
        }
        runtime.engine.eventLoop.enqueueMicrotask(task)
        assertFailsWith<CancellationException> {
            withContext(job) { runtime.eval("") }
        }
        assertEquals(1, calls)
        assertEquals(42.0, runtime.evalNumber("42"))
        assertEquals(1, calls)
    }

    @Test
    fun `已取消的调用在解析源码及执行空 AST 前立即退出`() = runTest {
        for (cacheSize in listOf(0, 2)) {
            val engine = Engine(parseCacheSize = cacheSize)
            val program = engine.parse("var mustNotExist;")
            assertFailsWith<CancellationException> {
                withContext(Job()) {
                    currentCoroutineContext().cancel()
                    // 语法错误也不应优先于已经生效的取消。
                    assertFailsWith<CancellationException> { engine.run("var =") }
                    assertFailsWith<CancellationException> { engine.run(program) }
                }
            }
            assertEquals(false, engine.globalObject.hasProperty(
                PropertyKey.Str("mustNotExist")
            ))
        }
    }

    @Test
    fun `async 协程取消不会包装为 InternalError 或触发拒绝回调`() = runTest {
        val runtime = TestRuntime()
        runtime.engine.registry.defineGlobal("stop", JSNativeFunction("stop") { _, _, _ ->
            throw CancellationException("中止独立 async 协程")
        })
        val promise = runtime.eval(
            """
            var rejected = false;
            async function work() { stop(); }
            var pending = work();
            pending.catch(function () { rejected = true; });
            pending;
            """.trimIndent()
        )
        assertEquals(JSPromise.State.PENDING, assertIs<JSPromise>(promise).state)
        assertEquals(false, runtime.evalBoolean("rejected"))
        // EventLoop 使用 SupervisorJob，单个 async 协程取消不应取消后续任务。
        runtime.eval("var fresh = 0; (async function () { fresh = await 42; })();")
        assertEquals(42.0, runtime.evalNumber("fresh"))
    }

    @Test
    fun `普通 JS 抛错仍然冲刷微任务并保留异常类型`() = runTest {
        val runtime = TestRuntime()
        assertFailsWith<JSException> {
            runtime.eval("var flushed = false; Promise.resolve().then(function () { flushed = true; }); throw 1;")
        }
        assertEquals(true, runtime.evalBoolean("flushed"))
    }

    @Test
    fun `宿主拒绝通知抛出的取消不会被事件循环忽略`() = runTest {
        val cancellation = CancellationException("中止宿主拒绝通知")
        val engine = Engine(DefaultHostEnvironment(
            unhandledRejectionHandler = { throw cancellation },
        ))
        assertSame(cancellation, assertFailsWith<CancellationException> {
            engine.run("Promise.reject('failure');")
        })
        // 已被取出的拒绝通知不会在下次运行时重复上报。
        engine.run("42")

        val handledEngine = Engine(DefaultHostEnvironment(
            rejectionHandledHandler = { throw cancellation },
        ))
        handledEngine.run("var pending = Promise.reject('failure');")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            handledEngine.run("pending.catch(function () {});")
        })
        handledEngine.run("42")
    }

    /**
     * 在宿主函数中取消本次独立 Job，并检查脚本未走到有限长任务的末尾。
     *
     * 只断言 withContext 抛取消不足以证明解释器有检查点：它本身在返回时也会
     * 检查取消，因此还要检查 JS 可观察的 finished 标记。
     */
    private suspend fun assertCancelledBeforeEnd(runtime: TestRuntime, task: String) {
        val job = Job()
        runtime.engine.registry.defineGlobal("cancelExecution", JSNativeFunction("cancelExecution") { _, _, _ ->
            job.cancel()
            JSUndefined
        })
        assertFailsWith<CancellationException> {
            withContext(job) {
                runtime.eval("var finished = false; cancelExecution(); $task finished = true;")
            }
        }
        assertEquals(false, runtime.evalBoolean("finished"), task.take(100))
    }
}
