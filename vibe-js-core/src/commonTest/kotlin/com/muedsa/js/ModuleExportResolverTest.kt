package com.muedsa.js

import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.module.internal.ModuleExportResolver
import com.muedsa.js.runtime.module.internal.ModuleGraph
import com.muedsa.js.runtime.module.internal.ModuleGraphResolver
import com.muedsa.js.runtime.module.internal.ResolveExportResult
import org.intellij.lang.annotations.Language
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 阶段 4：ResolveExport / export * / namespace 导出名；不涉及环境与求值。 */
@OptIn(ExperimentalModuleApi::class)
class ModuleExportResolverTest {

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
    fun `本地导出解析到当前模块的本地绑定`() = runTest {
        val graph = graph(
            source("a", "export const a = 1; export function f() {}"),
        )
        assertEquals(
            ResolveExportResult.Resolved(graph.entry, "a"),
            ModuleExportResolver.resolveExport(graph.entry, "a"),
        )
        assertEquals(
            ResolveExportResult.Resolved(graph.entry, "f"),
            ModuleExportResolver.resolveExport(graph.entry, "f"),
        )
        assertEquals(ResolveExportResult.NotFound, ModuleExportResolver.resolveExport(graph.entry, "missing"))
    }

    @Test
    fun `间接导出解析到目标模块的本地绑定`() = runTest {
        val graph = graph(
            source("a", "export { b as c } from \"./b.js\";"),
            mapOf("./b.js" to source("b", "export const b = 1;")),
        )
        val target = graph.records.getValue("b")
        assertEquals(
            ResolveExportResult.Resolved(target, "b"),
            ModuleExportResolver.resolveExport(graph.entry, "c"),
        )
    }

    @Test
    fun `export 星号排除 default 并支持显式覆盖`() = runTest {
        val graph = graph(
            source(
                "a",
                """
                export * from "./b.js";
                export const local = 3;
                """.trimIndent(),
            ),
            mapOf(
                "./b.js" to source(
                    "b",
                    "export default 1; export const x = 2; export { y as z }; const y = 4;",
                ),
            ),
        )
        val b = graph.records.getValue("b")
        assertEquals(ResolveExportResult.NotFound, ModuleExportResolver.resolveExport(graph.entry, "default"))
        assertEquals(
            ResolveExportResult.Resolved(b, "x"),
            ModuleExportResolver.resolveExport(graph.entry, "x"),
        )
        assertEquals(
            ResolveExportResult.Resolved(b, "y"),
            ModuleExportResolver.resolveExport(graph.entry, "z"),
        )
        assertEquals(
            ResolveExportResult.Resolved(graph.entry, "local"),
            ModuleExportResolver.resolveExport(graph.entry, "local"),
        )
        assertEquals(listOf("local", "x", "z"), ModuleExportResolver.exportedNames(graph.entry))
    }

    @Test
    fun `不同星号目标导出同名绑定产生 Ambiguous`() = runTest {
        val graph = graph(
            source("a", "export * from \"./b.js\"; export * from \"./c.js\";"),
            mapOf(
                "./b.js" to source("b", "export const x = 1;"),
                "./c.js" to source("c", "export const x = 2;"),
            ),
        )
        assertEquals(ResolveExportResult.Ambiguous, ModuleExportResolver.resolveExport(graph.entry, "x"))
    }

    @Test
    fun `相同星号目标经不同路径导出同一绑定不算歧义`() = runTest {
        val graph = graph(
            source("a", "export * from \"./b.js\"; export * from \"./c.js\";"),
            mapOf(
                "./b.js" to source("b", "export * from \"./d.js\";"),
                "./c.js" to source("c", "export * from \"./d.js\";"),
                "./d.js" to source("d", "export const x = 1;"),
            ),
        )
        val d = graph.records.getValue("d")
        assertEquals(
            ResolveExportResult.Resolved(d, "x"),
            ModuleExportResolver.resolveExport(graph.entry, "x"),
        )
    }

    @Test
    fun `export 星号 as 返回目标命名空间且不参与普通星号歧义`() = runTest {
        val graph = graph(
            source("a", "export * as ns from \"./b.js\";"),
            mapOf("./b.js" to source("b", "export const x = 1;")),
        )
        assertEquals(
            ResolveExportResult.Namespace(graph.records.getValue("b")),
            ModuleExportResolver.resolveExport(graph.entry, "ns"),
        )
        assertEquals(listOf("ns"), ModuleExportResolver.exportedNames(graph.entry))
    }

    @Test
    fun `星号循环不会无限递归`() = runTest {
        val graph = graph(
            source("a", "export * from \"./b.js\";"),
            mapOf(
                "./b.js" to source("b", "export * from \"./a.js\";"),
                "./a.js" to source("a", "export * from \"./b.js\";"),
            ),
        )
        assertEquals(ResolveExportResult.NotFound, ModuleExportResolver.resolveExport(graph.entry, "x"))
    }

    @Test
    fun `间接导出循环不会无限递归`() = runTest {
        val graph = graph(
            source("a", "export { x } from \"./b.js\";"),
            mapOf(
                "./b.js" to source("b", "export { x } from \"./a.js\";"),
                "./a.js" to source("a", "export { x } from \"./b.js\";"),
            ),
        )
        assertEquals(ResolveExportResult.NotFound, ModuleExportResolver.resolveExport(graph.entry, "x"))
    }
}
