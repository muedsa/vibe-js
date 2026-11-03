package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** JVM 大规模边界回归；其他目标通过 commonTest 验证有限迭代和配额中止。 */
class RuntimeCollectionLimitsTest {
    @Test
    fun `迭代值收集完整保留超过原百万上限的有限输入`() = runTest(timeout = 120.seconds) {
        val engine = Engine()
        try {
            var calls = 0
            // 重用结果对象与数字，避免为了测试计数边界创建百万份属性描述符。
            val step = JSObject().defineDataProperty(PropertyKey.Str("value"), JSNumber.ZERO)
                .defineDataProperty(PropertyKey.Str("done"), JSBoolean.FALSE)
            val done = JSObject().defineDataProperty(PropertyKey.Str("done"), JSBoolean.TRUE)
            val iterator = JSObject().defineDataProperty(PropertyKey.Str("next"),
                JSNativeFunction("next") { _, _, _ ->
                    if (calls++ < 1_000_001) step else done
                },
            )
            val source = JSObject().defineDataProperty(PropertyKey.Sym(engine.intrinsics.symbolIterator),
                JSNativeFunction("iterator") { _, _, _ -> iterator },
            )
            engine.registry.defineGlobal("collect", JSNativeFunction("collect") { interpreter, _, _ ->
                JSNumber(interpreter.iterateValues(source).size.toDouble())
            })
            assertEquals(1_000_001.0, assertIs<JSNumber>(engine.run("collect()")).value)
            assertEquals(1_000_002, calls)
        } finally {
            engine.close()
        }
    }
}
