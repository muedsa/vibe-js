package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.InMemoryModuleProvider
import com.muedsa.js.runtime.module.ModuleLinkErrorKind
import com.muedsa.js.runtime.module.ModuleLinkException
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.module.internal.ModuleGraphResolver
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 模块图规模边界：深依赖链与宽导出图。
 *
 * 只验证链接/求值在常见规模下可完成，不把耗时作为断言，避免不同平台抖动。
 */
@OptIn(ExperimentalModuleApi::class, ExperimentalCoroutinesApi::class)
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

    @Test
    fun `深链超过 maxModules 在边界停止`() = runTest {
        val depth = 32
        val sources = LinkedHashMap<String, String>()
        for (index in 1..depth) {
            sources["./m$index.js"] = if (index == depth) {
                "export const value = $index;"
            } else {
                "import './m${index + 1}.js'; export const value = $index;"
            }
        }
        var calls = 0
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? {
                calls += 1
                return sources[request.specifier]?.let {
                    ModuleSource(request.specifier, it, sourceName = request.specifier)
                }
            }
        }
        val error = assertFailsWith<ModuleLinkException> {
            ModuleGraphResolver(EngineLimits(maxModules = 16)).load(
                ModuleSource("entry", "import './m1.js';"),
                provider,
            )
        }
        assertEquals(ModuleLinkErrorKind.TOO_MANY_MODULES, error.kind)
        assertTrue(calls <= 16)
    }

    @Test
    fun `跨模块总源码长度超限`() = runTest {
        val sources = mapOf(
            "./m1.js" to "export const first = 1;",
            "./m2.js" to "export const second = 2;",
        )
        val error = assertFailsWith<ModuleLinkException> {
            ModuleGraphResolver(EngineLimits(maxTotalModuleSourceLength = 20)).load(
                ModuleSource("entry", "import './m1.js'; import './m2.js';"),
                InMemoryModuleProvider(sources),
            )
        }
        assertEquals(ModuleLinkErrorKind.TOTAL_SOURCE_TOO_LARGE, error.kind)
    }

    @Test
    fun `取消加载后不再继续调用 Provider`() = runTest {
        var calls = 0
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? {
                calls += 1
                if (calls == 2) awaitCancellation()
                return ModuleSource(request.specifier, "export const value = 1;")
            }
        }
        val job = launch {
            ModuleGraphResolver(EngineLimits()).load(
                ModuleSource("entry", "import './m1.js'; import './m2.js';"),
                provider,
            )
        }
        runCurrent()
        assertTrue(calls >= 2)
        job.cancelAndJoin()
        val callsAfterCancel = calls
        runCurrent()
        assertEquals(callsAfterCancel, calls)
        assertTrue(job.isCancelled)
    }
}
