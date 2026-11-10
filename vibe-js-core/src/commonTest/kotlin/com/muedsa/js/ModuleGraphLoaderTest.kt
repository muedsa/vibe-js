package com.muedsa.js

import com.muedsa.js.parser.ParseException
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleLinkErrorKind
import com.muedsa.js.runtime.module.ModuleLinkException
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.module.internal.ModuleGraphLoader
import com.muedsa.js.runtime.module.internal.ModuleStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 阶段 3：模块图递归加载、请求缓存、id 去重与预算；不涉及链接/求值。 */
@OptIn(ExperimentalModuleApi::class, ExperimentalCoroutinesApi::class)
class ModuleGraphLoaderTest {

    private class RecordingProvider(
        private val sources: Map<String, ModuleSource>,
        private val failure: Throwable? = null,
    ) : ModuleProvider {
        val requests = mutableListOf<ModuleRequest>()

        override suspend fun load(request: ModuleRequest): ModuleSource? {
            requests.add(request)
            failure?.let { throw it }
            return sources[request.specifier]
        }
    }

    private fun source(id: String, code: String, sourceName: String = id) =
        ModuleSource(id = id, source = code, sourceName = sourceName)

    @Test
    fun `递归加载依赖并按 requestedModules 顺序记录`() = runTest {
        val provider = RecordingProvider(
            mapOf(
                "./b.js" to source("b", "export const b = 1;"),
                "./c.js" to source("c", "export const c = 2;"),
            ),
        )
        val entry = source(
            "a",
            """
            import { b } from "./b.js";
            import { c } from "./c.js";
            """.trimIndent(),
        )
        val graph = ModuleGraphLoader(EngineLimits()).load(entry, provider)

        assertEquals("a", graph.entry.id)
        assertEquals(listOf("b", "c"), graph.entry.dependencies.map { it.id })
        assertEquals(listOf("a", "b", "c"), graph.records.keys.toList())
        assertTrue(graph.records.values.all { it.status == ModuleStatus.NEW })
        assertEquals(listOf("./b.js", "./c.js"), provider.requests.map { it.specifier })
        assertEquals(listOf("a", "a"), provider.requests.map { it.referrer })
    }

    @Test
    fun `循环依赖复用已创建的 record 而不递归`() = runTest {
        val entry = source("a", "import { b } from \"./b.js\"; export const a = 1;")
        val provider = RecordingProvider(
            mapOf(
                "./b.js" to source("b", "import { a } from \"./a.js\"; export const b = 1;"),
                "./a.js" to entry,
            ),
        )
        val graph = ModuleGraphLoader(EngineLimits()).load(entry, provider)

        assertEquals(2, graph.records.size)
        assertSame(graph.records["b"], graph.entry.dependencies.single())
        assertSame(graph.entry, graph.records["b"]?.dependencies?.single())
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun `菱形依赖的两个父模块共享同一个 record`() = runTest {
        val provider = RecordingProvider(
            mapOf(
                "./b.js" to source("b", "import { d } from \"./d.js\"; export const b = 1;"),
                "./c.js" to source("c", "import { d } from \"./d.js\"; export const c = 2;"),
                "./d.js" to source("d", "export const d = 3;"),
            ),
        )
        val entry = source(
            "a",
            "import { b } from \"./b.js\"; import { c } from \"./c.js\";",
        )
        val graph = ModuleGraphLoader(EngineLimits()).load(entry, provider)

        assertEquals(setOf("a", "b", "c", "d"), graph.records.keys)
        val shared = graph.records.getValue("d")
        assertSame(shared, graph.records.getValue("b").dependencies.single())
        assertSame(shared, graph.records.getValue("c").dependencies.single())
        assertEquals(4, provider.requests.size)
    }

    @Test
    fun `不同 specifier 指向同一 id 时复用同一 record`() = runTest {
        val provider = RecordingProvider(
            mapOf(
                "./b.js" to source("b", "export const b = 1;"),
                "./alias-b.js" to source("b", "export const b = 1;"),
            ),
        )
        val entry = source(
            "a",
            "import { b } from \"./b.js\"; import { b as alias } from \"./alias-b.js\";",
        )
        val graph = ModuleGraphLoader(EngineLimits()).load(entry, provider)

        assertEquals(listOf("a", "b"), graph.records.keys.toList())
        assertEquals(2, graph.entry.dependencies.size)
        assertSame(graph.entry.dependencies[0], graph.entry.dependencies[1])
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun `缺失依赖抛出 NOT_FOUND 并保留定位信息`() = runTest {
        val provider = RecordingProvider(emptyMap())
        val error = assertFailsWith<ModuleLinkException> {
            ModuleGraphLoader(EngineLimits()).load(
                source("a", "import { x } from \"./missing.js\";"),
                provider,
            )
        }
        assertEquals(ModuleLinkErrorKind.NOT_FOUND, error.kind)
        assertEquals("./missing.js", error.specifier)
        assertEquals("a", error.referrer)
    }

    @Test
    fun `Provider 异常原样透传`() = runTest {
        val failure = IllegalStateException("disk offline")
        val provider = RecordingProvider(emptyMap(), failure)
        val error = assertFailsWith<IllegalStateException> {
            ModuleGraphLoader(EngineLimits()).load(
                source("a", "import \"./dep.js\";"),
                provider,
            )
        }
        assertSame(failure, error)
    }

    @Test
    fun `模块数与源码总长度预算生效`() = runTest {
        val provider = RecordingProvider(
            mapOf("./b.js" to source("b", "export const b = 1;")),
        )
        val entry = source("a", "import { b } from \"./b.js\";")

        val tooMany = assertFailsWith<ModuleLinkException> {
            ModuleGraphLoader(EngineLimits(maxModules = 1)).load(entry, provider)
        }
        assertEquals(ModuleLinkErrorKind.TOO_MANY_MODULES, tooMany.kind)

        val tooLarge = assertFailsWith<ModuleLinkException> {
            ModuleGraphLoader(EngineLimits(maxTotalModuleSourceLength = 10)).load(entry, provider)
        }
        assertEquals(ModuleLinkErrorKind.TOTAL_SOURCE_TOO_LARGE, tooLarge.kind)
    }

    @Test
    fun `空 id 被拒绝而解析错误保持 ParseException`() = runTest {
        val loader = ModuleGraphLoader(EngineLimits())
        val invalid = assertFailsWith<ModuleLinkException> {
            loader.load(source("", "export const x = 1;"), RecordingProvider(emptyMap()))
        }
        assertEquals(ModuleLinkErrorKind.INVALID_MODULE, invalid.kind)

        assertFailsWith<ParseException> {
            loader.load(
                source("bad", "export { missing };"),
                RecordingProvider(emptyMap()),
            )
        }
    }

    @Test
    fun `按入口 specifier 加载时 referrer 为 null`() = runTest {
        val provider = RecordingProvider(
            mapOf("entry" to source("entry", "export const x = 1;")),
        )
        val graph = ModuleGraphLoader(EngineLimits()).load("entry", provider)

        assertEquals("entry", graph.entry.id)
        assertEquals(ModuleRequest("entry", referrer = null), provider.requests.single())
    }

    @Test
    fun `同一个 loader 重复 load 不保留上一张图`() = runTest {
        val loader = ModuleGraphLoader(EngineLimits())
        val provider = RecordingProvider(emptyMap())

        val first = loader.load(source("a", "export const a = 1;"), provider)
        assertEquals(setOf("a"), first.records.keys)

        val second = loader.load(source("b", "export const b = 2;"), provider)
        assertEquals(setOf("b"), second.records.keys)
        assertEquals("b", second.entry.id)
    }

    @Test
    fun `图加载可被协作取消`() = runTest {
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? = awaitCancellation()
        }
        val job = launch {
            ModuleGraphLoader(EngineLimits()).load(
                source("a", "import \"./b.js\";"),
                provider,
            )
        }
        runCurrent()
        assertTrue(job.isActive)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
}
