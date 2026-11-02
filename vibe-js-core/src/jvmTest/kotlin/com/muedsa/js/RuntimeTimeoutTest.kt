package com.muedsa.js

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * JVM 真实计时回归：Default 执行脚本，协程定时器可以在其他线程发出取消。
 * 不使用 runTest 的虚拟时钟，因为不挂起的纯计算不会推进虚拟时间。
 */
class RuntimeTimeoutTest {

    @Test
    fun `withTimeout 停止空体死循环且取消后同一 Engine 可复用`() = runTest(timeout = 10.seconds) {
        val runtime = TestRuntime()
        for (source in listOf("while (true) {}", "for (;;) ;", "do {} while (true);")) {
            val program = runtime.engine.parse("var kept = 42; $source")
            withContext(Dispatchers.Default) {
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(100) { runtime.engine.run(program) }
                }
            }
            assertEquals(42.0, runtime.evalNumber("kept"))
        }
    }

    @Test
    fun `JS catch 与 finally 无法吞掉死循环的宿主超时`() = runTest(timeout = 10.seconds) {
        val runtime = TestRuntime()
        val program = runtime.engine.parse(
            """
            var caught = false;
            var finalized = false;
            function work() {
                'use strict';
                for (let local = 0; ; local++) {
                    try { while (true) {} }
                    catch (e) { caught = true; }
                    finally { finalized = true; }
                }
            }
            work();
            """.trimIndent()
        )
        withContext(Dispatchers.Default) {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100) { runtime.engine.run(program) }
            }
        }
        assertEquals("false:false:undefined", runtime.evalString(
            "caught + ':' + finalized + ':' + typeof local"
        ))
    }

    @Test
    fun `微任务中的死循环可以超时且剩余回调不会污染下一次运行`() = runTest(timeout = 10.seconds) {
        val runtime = TestRuntime()
        val program = runtime.engine.parse(
            """
            var stale = false;
            Promise.resolve().then(function () { while (true) {} });
            Promise.resolve().then(function () { stale = true; });
            """.trimIndent()
        )
        withContext(Dispatchers.Default) {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100) { runtime.engine.run(program) }
            }
        }
        assertEquals(false, runtime.evalBoolean("stale"))
        runtime.eval("Promise.resolve().then(function () { stale = true; });")
        assertEquals(true, runtime.evalBoolean("stale"))
    }
}
