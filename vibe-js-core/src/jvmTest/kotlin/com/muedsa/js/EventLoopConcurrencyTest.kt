package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSUndefined
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** JVM 用真实线程验证竞争；生产实现仍只使用 commonMain 的标准原子与协程 API。 */
class EventLoopConcurrencyTest {
    @Test
    fun `并发run定时器和宿主恢复共享单一执行队列且不会丢失更新`() = runTest(timeout = 20.seconds) {
        withContext(Dispatchers.Default) {
            withTimeout(15000) {
                val engine = Engine()
                val active = AtomicInteger()
                val maximum = AtomicInteger()
                val gates = List(30) { CompletableDeferred<Unit>() }
                engine.registry.defineGlobal("waitHost", JSNativeFunction("waitHost") { _, _, args ->
                    gates[(args[0] as JSNumber).value.toInt()].await()
                    JSUndefined
                })
                engine.registry.defineGlobal("touch", JSNativeFunction("touch") { _, _, _ ->
                    val current = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, current) }
                    try { Thread.sleep(1) } finally { active.decrementAndGet() }
                    JSUndefined
                })
                try {
                    engine.run("""
                        var count = 0; async function work(i) { waitHost(i); touch(); count++; }
                        for (var i = 0; i < 30; i++) work(i);
                    """.trimIndent())
                    val scripts = List(20) { async { engine.run("touch(); count++;") } }
                    repeat(20) {
                        engine.eventLoop.schedule(0) {
                            // 定时器回调已在运行时入口内，不重入 Engine.run。
                            val interpreter = engine.interpreter.forkForCallback()
                            interpreter.runProgram(engine.parse("touch(); count++;"), engine.context.globalEnvironment)
                        }
                    }
                    gates.map { gate -> async { gate.complete(Unit) } }.awaitAll()
                    scripts.awaitAll()
                    engine.eventLoop.runUntilIdle()
                    assertEquals(1, maximum.get())
                    assertEquals(70.0, (engine.run("count") as JSNumber).value)
                    assertTrue(engine.eventLoop.pendingTasks.isIdle)
                } finally {
                    engine.close()
                }
            }
        }
    }

    @Test
    fun `关闭可从其他线程中止运行中的死循环并等待退出`() = runTest(timeout = 10.seconds) {
        withContext(Dispatchers.Default) {
            val engine = Engine()
            val entered = CompletableDeferred<Unit>()
            engine.registry.defineGlobal("entered", JSNativeFunction("entered") { _, _, _ ->
                entered.complete(Unit)
                JSUndefined
            })
            val running = async { engine.run("entered(); while (true) {}") }
            entered.await()
            withTimeout(5000) { engine.close() }
            assertFailsWith<CancellationException> { running.await() }
            assertTrue(engine.eventLoop.pendingTasks.isIdle)
        }
    }
}
