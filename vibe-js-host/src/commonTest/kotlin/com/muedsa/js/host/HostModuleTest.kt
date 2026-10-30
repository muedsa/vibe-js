package com.muedsa.js.host

import com.muedsa.js.runtime.Console
import com.muedsa.js.runtime.DefaultHostEnvironment
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.ScheduledTask
import com.muedsa.js.runtime.Scheduler
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 宿主模块测试（不依赖真实计时器）。
 */
class HostModuleTest {

    private class RecordingConsole : Console {
        val logs = mutableListOf<String>()
        override fun log(message: String) {
            logs.add(message)
        }

        override fun error(message: String) {
            logs.add("ERROR:$message")
        }
    }

    /**
     * 测试调度器：手动触发任务；支持取消。
     */
    private class QueueScheduler : Scheduler {
        val tasks = mutableListOf<suspend () -> Unit>()
        private val cancelled = mutableMapOf<Int, Boolean>()

        override fun schedule(delayMillis: Long, block: suspend () -> Unit): ScheduledTask {
            val index = tasks.size
            cancelled[index] = false
            tasks.add {
                if (cancelled[index] != true) {
                    block()
                }
            }
            return ScheduledTask { cancelled[index] = true }
        }

        suspend fun runAll() {
            while (tasks.isNotEmpty()) {
                val task = tasks.removeAt(0)
                task()
            }
        }
    }

    @Test
    fun `window 与 self 别名`() = runTest {
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop))
        val result = engine.run("window === globalThis && self === window && window.console === console")
        assertEquals(true, (result as JSBoolean).value)
    }

    @Test
    fun `console 输出`() = runTest {
        val output = RecordingConsole()
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop, output))
        engine.run("console.log('a', 1); console.error('bad');")
        assertEquals(listOf("a 1", "ERROR:bad"), output.logs)
    }

    @Test
    fun `setTimeout 与 clearTimeout`() = runTest {
        val scheduler = QueueScheduler()
        val engine = Engine(DefaultHostEnvironment(scheduler = scheduler))
        engine.install(HostModule(engine.eventLoop, RecordingConsole()))

        engine.run(
            """
            var first = 0;
            var second = 0;
            var firstId = setTimeout(function (x) { first = x; }, 10, 1);
            setTimeout(function (x) { second = x; }, 10, 2);
            clearTimeout(firstId);
            """.trimIndent()
        )
        scheduler.runAll()
        assertEquals(0.0, (engine.run("first") as JSNumber).value)
        assertEquals(2.0, (engine.run("second") as JSNumber).value)
    }

    @Test
    fun `queueMicrotask`() = runTest {
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop, RecordingConsole()))
        engine.run("var r = 0; queueMicrotask(function () { r = 7; });")
        assertEquals(7.0, (engine.run("r") as JSNumber).value)
    }

    @Test
    fun `atob 与 btoa`() = runTest {
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop))
        assertEquals("SGVsbG8=", (engine.run("btoa('Hello')") as JSString).value)
        assertEquals("Hello", (engine.run("atob('SGVsbG8=')") as JSString).value)
    }
}
