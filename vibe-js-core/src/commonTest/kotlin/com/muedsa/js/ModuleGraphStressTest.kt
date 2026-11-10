package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.InMemoryModuleProvider
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 模块图规模边界：深依赖链与宽导出图。
 *
 * 只验证链接/求值在常见规模下可完成，不把耗时作为断言，避免不同平台抖动。
 */
@OptIn(ExperimentalModuleApi::class)
class ModuleGraphStressTest {

    @Test
    fun `深依赖链按叶子到入口顺序求值`() = runTest {
        val depth = 128
        val sources = LinkedHashMap<String, String>()
        sources["entry"] = "import { value } from './m1.js'; export const result = value;"
        for (index in 1..depth) {
            sources["./m$index.js"] = if (index == depth) {
                "export const value = 1;"
            } else {
                "import { value as nextValue } from './m${index + 1}.js'; export const value = nextValue + 1;"
            }
        }
        val engine = Engine()
        val namespace = engine.runModule("entry", InMemoryModuleProvider(sources))
        assertEquals(
            depth.toDouble(),
            (engine.interpreter.getProperty(namespace, PropertyKey.Str("result")) as JSNumber).value,
        )
    }

    @Test
    fun `宽星号导出图保留全部导出名`() = runTest {
        val count = 128
        val sources = LinkedHashMap<String, String>()
        sources["entry"] = buildString {
            for (index in 0 until count) {
                append("export * from './m$index.js';\n")
            }
        }
        for (index in 0 until count) {
            sources["./m$index.js"] = "export const v$index = $index;"
        }
        val engine = Engine()
        val namespace = engine.runModule("entry", InMemoryModuleProvider(sources))
        val keys = namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value }
        assertEquals(count, keys.size)
        assertEquals(0.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("v0")) as JSNumber).value)
        assertEquals(
            (count - 1).toDouble(),
            (engine.interpreter.getProperty(namespace, PropertyKey.Str("v${count - 1}")) as JSNumber).value,
        )
    }
}
