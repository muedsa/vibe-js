package com.muedsa.js.host

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewHostRegressionTest {

    private suspend fun script(source: String): JSValue {
        val engine = Engine()
        engine.install(HostModule(engine.eventLoop, engine.host.console))
        return try {
            engine.run(source)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `普通对象克隆保留可枚举 length 属性`() = runTest {
        val result = script("""
            const clone = structuredClone({ length: 3, x: 1 });
            JSON.stringify(clone);
        """.trimIndent())
        assertEquals("""{"length":3,"x":1}""", (result as JSString).value)
    }

    @Test
    fun `正则克隆重置 lastIndex`() = runTest {
        val result = script("""
            const regex = /a/g;
            regex.lastIndex = 3;
            structuredClone(regex).lastIndex;
        """.trimIndent())
        assertEquals(0.0, (result as JSNumber).value)
    }

    @Test
    fun `克隆保留可调整缓冲区及视图长度模式`() = runTest {
        val result = script("""
            const b = new ArrayBuffer(2, { maxByteLength: 6 });
            const copy = structuredClone({
              b,
              tracking: new Uint8Array(b),
              fixed: new Uint8Array(b, 0, 2)
            });
            if (copy.b.resizable) {
              copy.b.resize(4);
            }
            [
              copy.b.resizable,
              copy.b.maxByteLength,
              copy.tracking.length,
              copy.fixed.length,
              copy.tracking.buffer === copy.b
            ].join(":");
        """.trimIndent())
        assertEquals("true:6:4:2:true", (result as JSString).value)
    }

    @Test
    fun `克隆数组先保存长度再读取元素`() = runTest {
        val result = script("""
            const a = [0];
            Object.defineProperty(a, "0", {
              get() {
                a.length = 3;
                return 7;
              }
            });
            const c = structuredClone(a);
            [c.length, c[0], a.length].join(":");
        """.trimIndent())
        assertEquals("1:7:3", (result as JSString).value)
    }
}
