package com.muedsa.js.conformance

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.value.JSNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 运行器执行设施的单元测试。
 *
 * 覆盖 T1 的核心契约：用例结果分类、引擎一定被关闭、超时与宿主取消的区分、
 * 以及命令行参数对超时/步数配额的覆盖。测试不依赖 Test262 语料。
 */
class RunSupportTest {

    @Test
    fun `用例成功时应返回结果并关闭引擎`() = runTest {
        val runtime = CaseRuntime(RunnerSettings())
        var captured: Engine? = null

        val run = runtime.withEngine { engine ->
            captured = engine
            (engine.run("1 + 1") as JSNumber).value
        }

        val outcome = assertIs<CaseOutcome.Success<Double>>(run.outcome)
        assertEquals(2.0, outcome.value)
        assertNull(run.cleanupFailure, "正常用例不应产生清理失败")
        assertTrue(captured!!.eventLoop.isClosed, "引擎必须在用例结束后关闭")
    }

    @Test
    fun `步数耗尽应归类为步数耗尽而不是内部异常`() = runTest {
        val runtime = CaseRuntime(RunnerSettings(limits = EngineLimits(maxSteps = 1_000)))
        var captured: Engine? = null

        val run = runtime.withEngine { engine ->
            captured = engine
            engine.run("while (true) {}")
        }

        val failure = assertIs<CaseOutcome.Failure>(run.outcome)
        assertEquals(FailureKind.STEP_LIMIT, failure.kind)
        assertNull(run.cleanupFailure)
        assertTrue(captured!!.eventLoop.isClosed, "配额耗尽后引擎仍必须关闭")
    }

    @Test
    fun `未预期异常应归类为运行器内部异常`() = runTest {
        val runtime = CaseRuntime(RunnerSettings())

        val run = runtime.withEngine {
            throw IllegalStateException("运行器故障")
        }

        val failure = assertIs<CaseOutcome.Failure>(run.outcome)
        assertEquals(FailureKind.INTERNAL, failure.kind)
        assertTrue(failure.message.contains("运行器故障"), "分类消息应保留原始异常信息")
    }

    @Test
    fun `超过用例时限应归类为超时并关闭引擎`() = runTest {
        // 虚拟时间下 delay 会立即推进，用例超时不需要真实等待
        val runtime = CaseRuntime(RunnerSettings(caseTimeoutMillis = 100))
        var captured: Engine? = null

        val run = runtime.withEngine { engine ->
            captured = engine
            delay(60_000)
            "不应到达"
        }

        val failure = assertIs<CaseOutcome.Failure>(run.outcome)
        assertEquals(FailureKind.TIMEOUT, failure.kind)
        assertNull(run.cleanupFailure)
        assertTrue(captured!!.eventLoop.isClosed, "超时后引擎必须被关闭")
    }

    @Test
    fun `宿主取消应原样传播而不是记为用例失败`() = runTest {
        val runtime = CaseRuntime(RunnerSettings())
        var captured: Engine? = null

        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.withEngine { engine ->
                captured = engine
                awaitCancellation()
            }
        }
        // 让 block 真正进入挂起状态后再取消
        yield()
        deferred.cancel()
        // join 等待取消清理完成，避免在 finally 关闭引擎之前断言
        deferred.join()

        assertFailsWith<CancellationException> { deferred.await() }
        assertTrue(captured!!.eventLoop.isClosed, "取消路径同样必须关闭引擎")
    }

    @Test
    fun `协作式死循环应在真实时限内被中止`() = runBlocking {
        // 使用真实时间：runTest 的虚拟时间无法打断同步 JS 循环。
        // 解释器每 1024 个检查点观察一次取消，因此死循环会在时限后很快退出。
        val runtime = CaseRuntime(RunnerSettings(caseTimeoutMillis = 500))
        var captured: Engine? = null

        val run = runtime.withEngine { engine ->
            captured = engine
            engine.run("while (true) {}")
        }

        val failure = assertIs<CaseOutcome.Failure>(run.outcome)
        assertEquals(FailureKind.TIMEOUT, failure.kind)
        assertNull(run.cleanupFailure)
        assertTrue(captured!!.eventLoop.isClosed, "中止后引擎必须关闭")
    }

    @Test
    fun `引擎关闭抛错时应作为清理失败返回而不是掩盖原结果`() = runBlocking {
        val runtime = CaseRuntime(RunnerSettings())
        val engine = Engine()
        // 在引擎自己的事件循环任务内调用 close 会被拒绝（EventLoop.close 的契约），
        // 用它来验证关闭失败被记录成可报告的字符串，而不是抛给调用方。
        val error = engine.eventLoop.runTask { runtime.closeEngine(engine) }

        assertNotNull(error, "关闭失败必须返回可报告的描述")
        assertTrue(error.contains("关闭失败"), "描述应标明关闭失败：$error")
        // 正常关闭，避免测试泄漏事件循环
        engine.close()
    }

    @Test
    fun `未提供参数时应使用默认配置且不限制步数`() {
        val settings = RunnerSettings.fromArgs(arrayOf("--strict"))

        assertEquals(RunnerSettings.DEFAULT_CASE_TIMEOUT_MILLIS, settings.caseTimeoutMillis)
        assertEquals(RunnerSettings.DEFAULT_CLEANUP_TIMEOUT_MILLIS, settings.cleanupTimeoutMillis)
        assertEquals(RunnerSettings.DEFAULT_NODE_TIMEOUT_MILLIS, settings.nodeTimeoutMillis)
        assertNull(settings.limits.maxSteps, "默认不应限制步数")
    }

    @Test
    fun `命令行参数应覆盖超时与步数配置`() {
        val settings = RunnerSettings.fromArgs(
            arrayOf(
                "--case-timeout-ms", "1234",
                "--cleanup-timeout-ms", "567",
                "--node-timeout-ms", "890",
                "--max-steps", "42",
            )
        )

        assertEquals(1_234L, settings.caseTimeoutMillis)
        assertEquals(567L, settings.cleanupTimeoutMillis)
        assertEquals(890L, settings.nodeTimeoutMillis)
        assertEquals(42L, settings.limits.maxSteps)
    }

    @Test
    fun `Node 进程超时应抛出 NodeTimeoutException`() {
        // 未安装 Node 的环境直接跳过：差分任务本身也会跳过
        val nodeAvailable = runCatching { runNodeProcess(listOf("--version"), 10_000) }.isSuccess
        if (!nodeAvailable) return

        val startedAt = System.nanoTime()
        val error = assertFailsWith<NodeTimeoutException> {
            runNodeProcess(listOf("-e", "setTimeout(function () {}, 60000);"), timeoutMillis = 500)
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(error.message!!.contains("500ms"), "超时消息应包含配置的时限")
        assertTrue(elapsedMillis < 30_000, "超时后应立即返回，而不是等待进程自行结束")
    }
}
