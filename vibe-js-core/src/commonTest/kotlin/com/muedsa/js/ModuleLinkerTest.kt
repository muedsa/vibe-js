package com.muedsa.js

import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleLinkErrorKind
import com.muedsa.js.runtime.module.ModuleLinkException
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.module.internal.ModuleGraph
import com.muedsa.js.runtime.module.internal.ModuleGraphResolver
import com.muedsa.js.runtime.module.internal.ModuleImportBinding
import com.muedsa.js.runtime.module.internal.ModuleLinker
import com.muedsa.js.runtime.module.internal.ModuleStatus
import org.intellij.lang.annotations.Language
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** 阶段 4：import 解析、TLA 拒绝、namespace 导出名与链接错误。 */
@OptIn(ExperimentalModuleApi::class)
class ModuleLinkerTest {

    private class Provider(
        private val sources: Map<String, ModuleSource>,
    ) : ModuleProvider {
        override suspend fun load(request: ModuleRequest): ModuleSource? = sources[request.specifier]
    }

    private suspend fun graph(
        entry: ModuleSource,
        sources: Map<String, ModuleSource> = emptyMap(),
    ): ModuleGraph = ModuleGraphResolver(EngineLimits()).load(entry, Provider(sources))

    private fun source(id: String, @Language("JavaScript") code: String) = ModuleSource(id, code)

    @Test
    fun `链接填充具名与命名空间 import 并标记 LINKED`() = runTest {
        val graph = graph(
            source(
                "a",
                """
                import { x as y } from "./b.js";
                import * as ns from "./b.js";
                export const a = y;
                """.trimIndent(),
            ),
            mapOf(
                "./b.js" to source(
                    "b",
                    "export const x = 1; export * as z from \"./c.js\";",
                ),
                "./c.js" to source("c", "export const q = 2;"),
            ),
        )
        ModuleLinker.link(graph)

        val a = graph.entry
        val b = graph.records.getValue("b")
        val c = graph.records.getValue("c")
        assertEquals(
            ModuleImportBinding.Resolved(b, "x"),
            a.importBindings["y"],
        )
        assertEquals(ModuleImportBinding.Namespace(b), a.importBindings["ns"])
        assertEquals(listOf("a"), a.exportedNames)
        assertEquals(listOf("x", "z"), b.exportedNames)
        assertEquals(listOf("q"), c.exportedNames)
        assertEquals(ModuleStatus.LINKED, a.status)
        assertEquals(ModuleStatus.LINKED, b.status)
        assertEquals(ModuleStatus.LINKED, c.status)
    }

    @Test
    fun `导入 export 星号 as 的命名空间导出`() = runTest {
        val graph = graph(
            source("a", "import { z } from \"./b.js\"; export const a = z;"),
            mapOf(
                "./b.js" to source("b", "export * as z from \"./c.js\";"),
                "./c.js" to source("c", "export const q = 2;"),
            ),
        )
        ModuleLinker.link(graph)

        assertEquals(
            ModuleImportBinding.Namespace(graph.records.getValue("c")),
            graph.entry.importBindings["z"],
        )
    }

    @Test
    fun `缺失导出抛出 MISSING_EXPORT 并保留字段`() = runTest {
        val graph = graph(
            source("a", "import { missing } from \"./b.js\";"),
            mapOf("./b.js" to source("b", "export const x = 1;")),
        )
        val error = assertFailsWith<ModuleLinkException> { ModuleLinker.link(graph) }

        assertEquals(ModuleLinkErrorKind.MISSING_EXPORT, error.kind)
        assertEquals("./b.js", error.specifier)
        assertEquals("a", error.referrer)
        assertEquals("b", error.moduleId)
        assertEquals("missing", error.exportName)
        assertEquals("a", error.sourceName)
        assertNotNull(error.range)
    }

    @Test
    fun `星号歧义在 import 解析时抛 AMBIGUOUS_EXPORT`() = runTest {
        val graph = graph(
            source("a", "import { x } from \"./b.js\";"),
            mapOf(
                "./b.js" to source("b", "export * from \"./c.js\"; export * from \"./d.js\";"),
                "./c.js" to source("c", "export const x = 1;"),
                "./d.js" to source("d", "export const x = 2;"),
            ),
        )
        val error = assertFailsWith<ModuleLinkException> { ModuleLinker.link(graph) }

        assertEquals(ModuleLinkErrorKind.AMBIGUOUS_EXPORT, error.kind)
        assertEquals("./b.js", error.specifier)
        assertEquals("a", error.referrer)
        assertEquals("b", error.moduleId)
        assertEquals("x", error.exportName)
        assertEquals("a", error.sourceName)
        assertNotNull(error.range)
    }

    @Test
    fun `模块图包含 TLA 时仍可链接`() = runTest {
        val graph = graph(source("a", "await 1;"))
        ModuleLinker.link(graph)

        assertEquals(ModuleStatus.LINKED, graph.entry.status)
    }

    @Test
    fun `合法链接可重复执行`() = runTest {
        val graph = graph(source("a", "export const x = 1;"))
        ModuleLinker.link(graph)
        ModuleLinker.link(graph)
        assertEquals(ModuleStatus.LINKED, graph.entry.status)
    }
}
