package com.muedsa.js.host

import com.muedsa.js.runtime.Console
import com.muedsa.js.runtime.DefaultHostEnvironment
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.ScheduledTask
import com.muedsa.js.runtime.Scheduler
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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

    @Test
    fun `console 的 info debug warn 与对象格式化`() = runTest {
        val output = RecordingConsole()
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop, output))
        engine.run(
            """
            console.info('i', 1);
            console.debug('d');
            console.warn('w');
            console.log({ a: 1 });
            console.error('e');
            """.trimIndent()
        )
        assertEquals(listOf("i 1", "d", "w", "[object Object]", "ERROR:e"), output.logs)
    }

    @Test
    fun `core 不注册任何宿主 API`() = runTest {
        val engine = Engine()
        assertEquals(
            "undefined:undefined:undefined:undefined",
            (engine.run(
                "typeof console + ':' + typeof setTimeout + ':' + typeof setInterval + ':' + typeof window"
            ) as JSString).value
        )
    }

    @Test
    fun `window 暴露定时器与 console`() = runTest {
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop, RecordingConsole()))
        assertEquals(
            true,
            (engine.run(
                "window.setTimeout === setTimeout && window.clearTimeout === clearTimeout && " +
                    "self.setInterval === setInterval && window.console === console"
            ) as JSBoolean).value
        )
        assertEquals(
            "[object console]",
            (engine.run("Object.prototype.toString.call(console)") as JSString).value
        )
    }

    @Test
    fun `定时器与微任务要求函数参数`() = runTest {
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop, RecordingConsole()))
        for (code in listOf(
            "setTimeout(1, 0);",
            "setInterval('code', 0);",
            "queueMicrotask(null);",
        )) {
            val error = assertFailsWith<JSException> { engine.run(code) }
            assertEquals("TypeError", (error.value as JSError).errorName)
        }
        // 未知 id 的清理调用应为无操作
        assertEquals(1.0, (engine.run("clearTimeout(999); clearInterval(999); 1") as JSNumber).value)
    }

    @Test
    fun `定时器回调异常输出到 console_error`() = runTest {
        val scheduler = QueueScheduler()
        val output = RecordingConsole()
        val engine = Engine(DefaultHostEnvironment(scheduler = scheduler))
        engine.install(HostModule(engine.eventLoop, output))
        engine.run("setTimeout(function () { throw new Error('boom'); }, 0);")
        scheduler.runAll()
        assertEquals(1, output.logs.size)
        assertTrue(output.logs[0].startsWith("ERROR:Error: boom"))
    }

    @Test
    fun `atob 解码非法字符串抛 InvalidCharacterError`() = runTest {
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop, RecordingConsole()))
        val error = assertFailsWith<JSException> { engine.run("atob('!!!')") }
        assertEquals("InvalidCharacterError", (error.value as JSError).errorName)
    }

    @Test
    fun `微任务先于定时器宏任务执行`() = runTest {
        val scheduler = QueueScheduler()
        val engine = Engine(DefaultHostEnvironment(scheduler = scheduler))
        engine.install(HostModule(engine.eventLoop, RecordingConsole()))
        engine.run(
            """
            var log = [];
            setTimeout(function () { log.push('t'); }, 0);
            Promise.resolve().then(function () { log.push('p'); });
            queueMicrotask(function () { log.push('m'); });
            """.trimIndent()
        )
        assertEquals("p,m", (engine.run("log.join(',')") as JSString).value)
        scheduler.runAll()
        assertEquals("p,m,t", (engine.run("log.join(',')") as JSString).value)
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `注入的时钟决定 Date 当前时间`() = runTest {
        val fixedTime = 1_700_000_000_000L
        val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(fixedTime)
        }
        val engine = Engine(DefaultHostEnvironment(clock = clock))
        assertEquals(fixedTime.toDouble(), (engine.run("Date.now()") as JSNumber).value)
        assertEquals(fixedTime.toDouble(), (engine.run("new Date().getTime()") as JSNumber).value)
    }

    @Test
    fun `setInterval 周期执行并可被 clearInterval 停止`() = runTest {
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop, RecordingConsole()))
        engine.run("var ticks = 0; var id = setInterval(function (step) { ticks += step; }, 1, 1);")
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while ((engine.run("ticks") as JSNumber).value < 3) {
                        delay(5)
                    }
                }
            }
        } finally {
            engine.run("clearInterval(id);")
        }
        withContext(Dispatchers.Default) { delay(30) }
        val afterClear = (engine.run("ticks") as JSNumber).value
        withContext(Dispatchers.Default) { delay(40) }
        assertEquals(afterClear, (engine.run("ticks") as JSNumber).value)
    }
}
