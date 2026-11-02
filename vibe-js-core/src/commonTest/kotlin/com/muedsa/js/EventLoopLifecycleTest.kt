package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EventLoop
import com.muedsa.js.runtime.ScheduledTask
import com.muedsa.js.runtime.Scheduler
import com.muedsa.js.runtime.exception.EventLoopClosedException
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSUndefined
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 生命周期测试使用可控调度器，不依赖真实计时器或平台线程。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventLoopLifecycleTest {
    private class ManualScheduler : Scheduler {
        private class Task(val block: suspend () -> Unit, var cancelled: Boolean = false)
        private val tasks = ArrayDeque<Task>()
        override fun schedule(delayMillis: Long, block: suspend () -> Unit): ScheduledTask {
            val task = Task(block)
            tasks.addLast(task)
            return ScheduledTask { task.cancelled = true }
        }
        suspend fun fireNext() {
            val task = tasks.removeFirst()
            if (!task.cancelled) task.block()
        }
    }

    @Test
    fun `空闲等待包含未来定时器及其微任务且任务快照归零`() = runTest {
        val scheduler = ManualScheduler()
        val loop = EventLoop(scheduler)
        val order = mutableListOf<String>()
        loop.enqueueMicrotask { order.add("micro") }
        loop.schedule(100) { order.add("timer"); loop.enqueueMicrotask { order.add("after") } }
        assertEquals(1, loop.pendingTasks.microtasks)
        assertEquals(1, loop.pendingTasks.timers)
        val waiting = async { loop.runUntilIdle() }
        runCurrent()
        assertEquals(listOf("micro"), order)
        assertFalse(waiting.isCompleted)
        scheduler.fireNext()
        waiting.await()
        assertEquals(listOf("micro", "timer", "after"), order)
        assertTrue(loop.pendingTasks.isIdle)
        loop.close()
    }

    @Test
    fun `宿主恢复async后自动推进await和Promise链无需额外run`() = runTest {
        val engine = Engine()
        val gate = CompletableDeferred<Unit>()
        engine.registry.defineGlobal("waitHost", JSNativeFunction("waitHost") { _, _, _ ->
            gate.await(); JSUndefined
        })
        val promise = assertIs<JSPromise>(engine.run(
            "async function work() { waitHost(); await 0; return 40; } work().then(x => x + 2);"
        ))
        assertTrue(engine.eventLoop.pendingTasks.asyncTasks > 0)
        gate.complete(Unit)
        engine.eventLoop.runUntilIdle()
        assertEquals(42.0, assertIs<JSNumber>(promise.awaitResult()).value)
        assertTrue(engine.eventLoop.pendingTasks.isIdle)
        engine.close()
    }

    @Test
    fun `关闭取消定时器微任务与async并等待非取消清理完成`() = runTest {
        val engine = Engine()
        val waiting = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var cleaned = false
        engine.registry.defineGlobal("waitHost", JSNativeFunction("waitHost") { _, _, _ ->
            try { waiting.await() } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                    cleaned = true
                }
            }
            JSUndefined
        })
        val promise = assertIs<JSPromise>(engine.run("async function work() { waitHost(); return 1; } work();"))
        var stale = false
        engine.eventLoop.schedule(60000) { stale = true }
        engine.eventLoop.enqueueMicrotask { stale = true }
        val closing = async { engine.close() }
        cleanupStarted.await()
        assertTrue(engine.eventLoop.isClosed)
        assertFalse(closing.isCompleted)
        releaseCleanup.complete(Unit)
        closing.await()
        assertTrue(cleaned)
        assertFalse(stale)
        assertEquals(JSPromise.State.PENDING, promise.state)
        assertTrue(engine.eventLoop.pendingTasks.isIdle)
        // 重复 close 安全，关闭不把生命周期控制转换成 Promise 拒绝。
        engine.close()
        assertFailsWith<EventLoopClosedException> { engine.run("42") }
        assertFailsWith<EventLoopClosedException> { engine.run(engine.parse("42")) }
        assertFailsWith<EventLoopClosedException> { engine.eventLoop.enqueueMicrotask {} }
        assertFailsWith<EventLoopClosedException> { engine.eventLoop.schedule(0) {} }
        assertFailsWith<EventLoopClosedException> { engine.eventLoop.runUntilIdle() }
    }

    @Test
    fun `关闭中止在途run而不取消宿主调用方和外部scope`() = runTest {
        val engine = Engine()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var cleaned = false
        engine.registry.defineGlobal("hold", JSNativeFunction("hold") { _, _, _ ->
            entered.complete(Unit)
            try { gate.await() } finally { cleaned = true }
            JSUndefined
        })
        val task = async { engine.run("hold()") }
        entered.await()
        engine.close()
        assertFailsWith<CancellationException> { task.await() }
        assertTrue(cleaned)
        assertTrue(currentCoroutineContext().isActive)
        assertTrue(engine.eventLoop.pendingTasks.isIdle)
        val loop = EventLoop(scope = this)
        loop.close()
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test
    fun `任务内部拒绝等待自身关闭或重入且循环仍可使用`() = runTest {
        val loop = EventLoop()
        loop.runTask {
            assertFailsWith<IllegalStateException> { loop.close() }
            assertFailsWith<IllegalStateException> { loop.runUntilIdle() }
            assertFailsWith<IllegalStateException> { loop.runTask {} }
        }
        assertFalse(loop.isClosed)
        assertEquals(42, loop.runTask { 42 })
        loop.close()
    }

    @Test
    fun `周期任务阻止空闲直到取消且取消等待不关闭循环`() = runTest {
        val scheduler = ManualScheduler()
        val loop = EventLoop(scheduler)
        var ticks = 0
        val interval = loop.scheduleInterval(1) { ticks++ }
        scheduler.fireNext()
        assertEquals(1, ticks)
        assertEquals(1, loop.pendingTasks.timers)
        assertFailsWith<CancellationException> { withTimeout(1) { loop.runUntilIdle() } }
        assertFalse(loop.isClosed)
        interval.cancel()
        loop.runUntilIdle()
        scheduler.fireNext()
        assertEquals(1, ticks)
        loop.close()
    }

    @Test
    fun `定时器抛错仍执行微任务检查点且不会遗留任务计数`() = runTest {
        val scheduler = ManualScheduler()
        val loop = EventLoop(scheduler)
        var completed = false
        val failure = IllegalArgumentException("timer failure")
        loop.schedule(0) {
            loop.enqueueMicrotask { completed = true }
            throw failure
        }
        assertFailsWith<IllegalArgumentException> { scheduler.fireNext() }
        loop.runUntilIdle()
        assertTrue(completed)
        assertTrue(loop.pendingTasks.isIdle)
        // 宿主控制流与普通错误不同：显式取消不允许剩余微任务继续执行。
        completed = false
        loop.schedule(0) {
            loop.enqueueMicrotask { completed = true }
            throw CancellationException("stop timer")
        }
        assertFailsWith<CancellationException> { scheduler.fireNext() }
        loop.runUntilIdle()
        assertFalse(completed)
        loop.close()
    }

    @Test
    fun `关闭等待已经触发的定时器回调清理而不继续执行回调`() = runTest {
        val scheduler = ManualScheduler()
        val loop = EventLoop(scheduler)
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var cleaned = false
        var continued = false
        loop.schedule(0) {
            entered.complete(Unit)
            try { gate.await() } finally { cleaned = true }
            continued = true
        }
        val firing = async { scheduler.fireNext() }
        entered.await()
        loop.close()
        firing.await()
        assertTrue(cleaned)
        assertFalse(continued)
        assertTrue(loop.pendingTasks.isIdle)
    }

    @Test
    fun `Promise回调内恢复原async不会覆盖原函数的词法帧`() = runTest {
        val engine = Engine()
        val gate = CompletableDeferred<Unit>()
        engine.registry.defineGlobal("waitHost", JSNativeFunction("waitHost") { _, _, _ -> gate.await(); JSUndefined })
        engine.registry.defineGlobal("releaseHost", JSNativeFunction("releaseHost") { _, _, _ -> gate.complete(Unit); JSUndefined })
        val promise = assertIs<JSPromise>(engine.run("""
            async function work() {
                let local = 42;
                Promise.resolve().then(function callback() { let local = 99; releaseHost(); });
                waitHost();
                return local;
            }
            work();
        """.trimIndent()))
        engine.eventLoop.runUntilIdle()
        assertEquals(42.0, assertIs<JSNumber>(promise.awaitResult()).value)
        engine.close()
    }
}
