package com.muedsa.js.host

import com.muedsa.js.runtime.DefaultHostEnvironment
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.HostEnvironment
import com.muedsa.js.runtime.MonotonicClock
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SelfAuditHostRegressionTest {

    private suspend fun script(source: String): JSValue {
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop, engine.host.console))
        return try {
            engine.run(source)
        } finally {
            engine.close()
        }
    }

    private fun text(value: JSValue): String = when (value) {
        is JSString -> value.value
        else -> value.toPrimitiveString()
    }

    @Test
    fun `TextDecoder 非法三字节和四字节序列只产生一个替换字符`() = runTest {
        val result = script(
            """
            function points(bytes) {
              return Array.from(new TextDecoder().decode(new Uint8Array(bytes)))
                .map((char) => char.codePointAt(0).toString(16))
                .join(":");
            }
            points([225, 128, 65]) + "|" + points([240, 144, 128, 65]);
            """.trimIndent(),
        )
        assertEquals("fffd:41|fffd:41", text(result))
    }

    @Test
    fun `TextDecoder 空 decode 不消耗 BOM 状态`() = runTest {
        val result = script(
            """
            const decoder = new TextDecoder();
            decoder.decode();
            const value = decoder.decode(new Uint8Array([239, 187, 191, 65]));
            value.length + ":" + value;
            """.trimIndent(),
        )
        assertEquals("1:A", text(result))
    }

    @Test
    fun `structuredClone 保留 DataView 长度跟踪`() = runTest {
        val result = script(
            """
            const buffer = new ArrayBuffer(2, { maxByteLength: 6 });
            const copy = structuredClone({
              buffer,
              tracking: new DataView(buffer),
              fixed: new DataView(buffer, 0, 2)
            });
            copy.buffer.resize(4);
            copy.tracking.byteLength + ":" + copy.fixed.byteLength;
            """.trimIndent(),
        )
        assertEquals("4:2", text(result))
    }

    @Test
    fun `structuredClone 保留 Error 名称消息和原型`() = runTest {
        val result = script(
            """
            const copy = structuredClone(new TypeError("boom"));
            copy.name + ":" + copy.message + ":" + String(copy instanceof TypeError);
            """.trimIndent(),
        )
        assertEquals("TypeError:boom:true", text(result))
    }

    @Test
    fun `URL 无效 port protocol hostname 赋值应忽略`() = runTest {
        val result = script(
            """
            const url = new URL("https://a:8080/x");
            url.port = "abc";
            url.protocol = "1";
            url.hostname = "";
            url.href;
            """.trimIndent(),
        )
        assertEquals("https://a:8080/x", text(result))
    }

    @Test
    fun `URLSearchParams 实例可迭代且支持 record 初始化`() = runTest {
        val result = script(
            """
            const params = new URLSearchParams({ a: "1", b: "2" });
            typeof params[Symbol.iterator] + ":" +
              Array.from(params).map((entry) => entry.join("=")).join(",") + ":" +
              params.toString();
            """.trimIndent(),
        )
        assertEquals("function:a=1,b=2:a=1&b=2", text(result))
    }

    @Test
    fun `URL 保留空 query 和 fragment`() = runTest {
        val result = script(
            """
            [new URL("http://a/p?").href, new URL("http://a/p#").href].join("|");
            """.trimIndent(),
        )
        assertEquals("http://a/p?|http://a/p#", text(result))
    }

    @Test
    fun `await 已捕获的拒绝不报告 unhandled`() = runTest {
        val unhandled = mutableListOf<String>()
        val handled = mutableListOf<String>()
        val host = RejectionRecordingHost(unhandled, handled)
        val engine = Engine(host)
        try {
            engine.run(
                """
                (async function () {
                  try {
                    await Promise.reject("caught");
                  } catch (error) {
                  }
                })();
                """.trimIndent(),
            )
            assertEquals(emptyList(), unhandled)
            assertEquals(emptyList(), handled)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `已上报的拒绝随后被 await 时补发 rejectionhandled`() = runTest {
        val unhandled = mutableListOf<String>()
        val handled = mutableListOf<String>()
        val host = RejectionRecordingHost(unhandled, handled)
        val engine = Engine(host)
        try {
            engine.run(
                """
                globalThis.promise = Promise.reject("late");
                """.trimIndent(),
            )
            engine.run(
                """
                (async function () {
                  try {
                    await globalThis.promise;
                  } catch (error) {
                  }
                })();
                """.trimIndent(),
            )
            assertEquals(listOf("late"), unhandled)
            assertEquals(listOf("late"), handled)
        } finally {
            engine.close()
        }
    }

    private class RejectionRecordingHost(
        private val unhandled: MutableList<String>,
        private val handled: MutableList<String>,
    ) : HostEnvironment {
        private val defaults = DefaultHostEnvironment()
        override val console = defaults.console
        override val clock = defaults.clock
        override val scheduler = defaults.scheduler
        override val monotonicClock: MonotonicClock = defaults.monotonicClock

        override fun reportUnhandledRejection(reason: JSValue) {
            unhandled.add(if (reason is JSString) reason.value else reason.toPrimitiveString())
        }

        override fun reportRejectionHandled(reason: JSValue) {
            handled.add(if (reason is JSString) reason.value else reason.toPrimitiveString())
        }
    }
}
