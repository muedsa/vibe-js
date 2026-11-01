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

    /** 记录调度延迟的测试调度器；任务按入队顺序手动执行。 */
    private class DelayRecordingScheduler : Scheduler {
        val delays = mutableListOf<Long>()
        private val tasks = mutableListOf<suspend () -> Unit>()

        override fun schedule(delayMillis: Long, block: suspend () -> Unit): ScheduledTask {
            delays.add(delayMillis)
            tasks.add(block)
            return ScheduledTask { }
        }

        suspend fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0)()
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
    fun `queueMicrotask 回调抛错记录到 console 且继续执行`() = runTest {
        val output = RecordingConsole()
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop, output))
        engine.run(
            "var done = false; queueMicrotask(function () { throw new Error('boom'); }); " +
                "queueMicrotask(function () { done = true; });"
        )
        assertEquals(true, (engine.run("done") as JSBoolean).value)
        assertTrue(output.logs.last().startsWith("ERROR:Error: boom"))
    }

    @Test
    fun `atob 与 btoa`() = runTest {
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop))
        assertEquals("SGVsbG8=", (engine.run("btoa('Hello')") as JSString).value)
        assertEquals("Hello", (engine.run("atob('SGVsbG8=')") as JSString).value)
    }

    @Test
    fun `atob 与 btoa 按 WHATWG 使用 Latin-1 与宽松解码`() = runTest {
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop))
        // Latin-1 码元：é(0xE9) 编码为 6Q==，而不是 UTF-8 的 w6k=
        assertEquals("6Q==", (engine.run("btoa('é')") as JSString).value)
        assertEquals("/w==", (engine.run("btoa(String.fromCharCode(255))") as JSString).value)
        // 超过 U+00FF 的码元抛 InvalidCharacterError
        val encodeError = assertFailsWith<JSException> { engine.run("btoa('漢')") }
        assertEquals("InvalidCharacterError", (encodeError.value as JSError).errorName)
        // atob 每个字节映射为一个 Latin-1 码元（w6k= 是两个字节，不是 UTF-8 的 é）
        assertEquals("\u00C3\u00A9", (engine.run("atob('w6k=')") as JSString).value)
        // 宽松解码：缺少填充、任意 ASCII 空白、忽略末端未使用比特
        assertEquals("a", (engine.run("atob('YQ')") as JSString).value)
        assertEquals("a", (engine.run("atob(' Y Q = = ')") as JSString).value)
        assertEquals("a", (engine.run("atob('YR==')") as JSString).value)
        // 非法填充与长度抛 InvalidCharacterError
        for (expression in listOf("atob('YQ=')", "atob('====')", "atob('A')", "atob('abcde')")) {
            val failure = assertFailsWith<JSException> { engine.run(expression) }
            assertEquals("InvalidCharacterError", (failure.value as JSError).errorName, expression)
        }
        // 0..255 全字节往返
        assertEquals(
            true,
            (engine.run(
                "(function () { var s = ''; for (var i = 0; i < 256; i++) { " +
                    "s += String.fromCharCode(i); } return atob(btoa(s)) === s; })()"
            ) as JSBoolean).value
        )
        // 宽松填充与尾部未使用比特：全部与 WHATWG/Node 行为一致
        val validPairs = mapOf(
            "AA==" to "\u0000",
            "AAA=" to "\u0000\u0000",
            "AAAA" to "\u0000\u0000\u0000",
            "AAA" to "\u0000\u0000",
            "AA" to "\u0000",
            "AB" to "\u0000",
            "+/" to "\u00FB",
            "   " to "",
        )
        for ((encoded, decoded) in validPairs) {
            assertEquals(decoded, (engine.run("atob('$encoded')") as JSString).value, encoded)
        }
        // 填充位置/长度/非 ASCII 空白/URL 安全字符均非法
        for (encoded in listOf("=", "AA=A", "A===", "AA===", "AA==AA==", "YQ=", "\u00A0YQ==", "-_")) {
            val failure = assertFailsWith<JSException> { engine.run("atob('$encoded')") }
            assertEquals("InvalidCharacterError", (failure.value as JSError).errorName, encoded)
        }
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

    @Test
    fun `setTimeout 额外参数与延迟钳制及全局别名身份`() = runTest {
        val scheduler = DelayRecordingScheduler()
        val engine = Engine(DefaultHostEnvironment(scheduler = scheduler))
        engine.install(HostModule(engine.eventLoop, RecordingConsole()))
        assertEquals(
            true,
            (engine.run("window === self && window.window === window && self.self === self")
                as JSBoolean).value
        )
        engine.run(
            """
            var out = [];
            setTimeout(function (a, b) { out.push(a + b); }, 5, 1, 2);
            setTimeout(function () { out.push('n'); }, -3);
            """.trimIndent()
        )
        assertEquals(listOf(5L, 0L), scheduler.delays)
        scheduler.runAll()
        assertEquals("3,n", (engine.run("out.join(',')") as JSString).value)
    }

    @Test
    fun `queueMicrotask 内再次入队先于定时器`() = runTest {
        val scheduler = DelayRecordingScheduler()
        val engine = Engine(DefaultHostEnvironment(scheduler = scheduler))
        engine.install(HostModule(engine.eventLoop, RecordingConsole()))
        engine.run(
            """
            var log = [];
            setTimeout(function () { log.push('t'); }, 0);
            queueMicrotask(function () {
              log.push('a');
              queueMicrotask(function () { log.push('b'); });
            });
            """.trimIndent()
        )
        assertEquals("a,b", (engine.run("log.join(',')") as JSString).value)
        scheduler.runAll()
        assertEquals("a,b,t", (engine.run("log.join(',')") as JSString).value)
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

    @OptIn(ExperimentalTime::class)
    @Test
    fun `console time 使用注入时钟并处理重复与缺失标签`() = runTest {
        var nowMillis = 1_000L
        val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
        }
        val output = RecordingConsole()
        val engine = Engine(DefaultHostEnvironment(clock = clock))
        engine.install(HostModule(engine.eventLoop, output))
        engine.run("console.time('t')")
        nowMillis += 250
        engine.run("console.timeEnd('t')")
        assertEquals("t: 250ms", output.logs.last())
        engine.run("console.timeEnd('missing')")
        assertTrue(output.logs.last().contains("does not exist"))
        engine.run("console.time('dup')")
        engine.run("console.time('dup')")
        assertTrue(output.logs.last().contains("already exists"))
        nowMillis += 10
        engine.run("console.timeEnd('dup')")
        assertEquals("dup: 10ms", output.logs.last())
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `performance 使用注入时钟并固定时间原点`() = runTest {
        var nowMillis = 1_000_000L
        val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
        }
        val engine = Engine(DefaultHostEnvironment(clock = clock))
        engine.install(HostModule(engine.eventLoop, RecordingConsole()))
        assertEquals(0.0, (engine.run("performance.now()") as JSNumber).value)
        assertEquals(1_000_000.0, (engine.run("performance.timeOrigin") as JSNumber).value)
        nowMillis += 250
        assertEquals(250.0, (engine.run("performance.now()") as JSNumber).value)
        assertEquals(
            "[object Performance]",
            (engine.run("Object.prototype.toString.call(performance)") as JSString).value,
        )
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

    @Test
    fun `setInterval 回调抛错后继续执行并记录错误`() = runTest {
        val output = RecordingConsole()
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop, output))
        engine.run("var ticks = 0; var id = setInterval(function () { ticks += 1; throw new Error('boom'); }, 1);")
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while ((engine.run("ticks") as JSNumber).value < 2) {
                        delay(5)
                    }
                }
            }
        } finally {
            engine.run("clearInterval(id);")
        }
        assertTrue(output.logs.isNotEmpty())
        assertTrue(output.logs[0].startsWith("ERROR:Error: boom"))
    }

    @Test
    fun `回调内 clearInterval 停止后续触发`() = runTest {
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop, RecordingConsole()))
        engine.run(
            "var ticks = 0; var id = setInterval(function () { ticks += 1; " +
                "if (ticks === 2) clearInterval(id); }, 1);"
        )
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while ((engine.run("ticks") as JSNumber).value < 2) {
                    delay(5)
                }
            }
        }
        withContext(Dispatchers.Default) { delay(40) }
        assertEquals(2.0, (engine.run("ticks") as JSNumber).value)
        engine.run("clearInterval(id);")
    }

    @Test
    fun `clearInterval 可取消 setTimeout 且 clearTimeout 可取消 setInterval`() = runTest {
        // QueueScheduler 保证 setTimeout 任务在断言前不会自动执行
        val scheduler = QueueScheduler()
        val engine = Engine(DefaultHostEnvironment(scheduler = scheduler))
        engine.install(HostModule(engine.eventLoop, RecordingConsole()))
        engine.run(
            """
            var timeoutRan = false;
            var timeoutId = setTimeout(function () { timeoutRan = true; }, 0);
            clearInterval(timeoutId);
            """.trimIndent()
        )
        scheduler.runAll()
        assertEquals(false, (engine.run("timeoutRan") as JSBoolean).value)

        // 在同一脚本内立即取消：脚本执行期间持有运行时锁，interval 首次 tick 不会先于取消
        engine.run(
            """
            var ticks = 0;
            var intervalId = setInterval(function () { ticks += 1; }, 1);
            clearTimeout(intervalId);
            """.trimIndent()
        )
        withContext(Dispatchers.Default) { delay(30) }
        assertEquals(0.0, (engine.run("ticks") as JSNumber).value)
    }
}
