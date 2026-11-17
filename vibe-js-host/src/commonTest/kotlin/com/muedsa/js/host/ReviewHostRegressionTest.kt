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
}
