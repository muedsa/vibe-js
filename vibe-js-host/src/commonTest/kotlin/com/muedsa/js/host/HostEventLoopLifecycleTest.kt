package com.muedsa.js.host

import com.muedsa.js.runtime.DefaultHostEnvironment
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.ScheduledTask
import com.muedsa.js.runtime.Scheduler
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 宿主定时器统一使用核心生命周期，测试完全由手动调度器驱动。 */
class HostEventLoopLifecycleTest {
    private class ManualScheduler : Scheduler {
        val tasks = ArrayDeque<suspend () -> Unit>()
        val delays = mutableListOf<Long>()
        var cancellations = 0
        override fun schedule(delayMillis: Long, block: suspend () -> Unit): ScheduledTask {
            delays.add(delayMillis)
            // 故意保留取消后的唤醒，验证 EventLoop 自身也会阻止迟到回调。
            tasks.addLast(block)
            return ScheduledTask { cancellations++ }
        }
        suspend fun fireNext() { tasks.removeFirst()() }
    }

    @Test
    fun `interval复用宿主Scheduler且在自身回调中清除不会截断当前代码`() = runTest {
        val scheduler = ManualScheduler()
        val engine = Engine(DefaultHostEnvironment(scheduler = scheduler))
        engine.install(HostModule(engine.eventLoop))
        engine.run("var order = ''; var id = setInterval(function() { " +
            "order += 'tick'; clearInterval(id); order += ':done'; " +
            "Promise.resolve().then(() => { order += ':micro'; }); }, 7);")
        assertEquals(listOf(7L), scheduler.delays)
        scheduler.fireNext()
        engine.eventLoop.runUntilIdle()
        assertEquals("tick:done:micro", (engine.run("order") as JSString).value)
        assertTrue(scheduler.tasks.isEmpty())
        engine.close()
    }

    @Test
    fun `关闭同时取消timeout和interval并屏蔽迟到的调度唤醒`() = runTest {
        val scheduler = ManualScheduler()
        val engine = Engine(DefaultHostEnvironment(scheduler = scheduler))
        engine.install(HostModule(engine.eventLoop))
        engine.run("var ticks = 0; setTimeout(() => ticks++, 1); setInterval(() => ticks++, 2);")
        assertEquals(2, engine.eventLoop.pendingTasks.timers)
        engine.close()
        assertEquals(2, scheduler.cancellations)
        while (scheduler.tasks.isNotEmpty()) scheduler.fireNext()
        assertTrue(engine.eventLoop.pendingTasks.isIdle)
        assertEquals(0.0, (engine.globalObject.getOwnDataValue(PropertyKey.Str("ticks")) as JSNumber).value)
    }
}
