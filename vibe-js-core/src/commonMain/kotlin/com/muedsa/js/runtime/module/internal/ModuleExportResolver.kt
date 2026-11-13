@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.ast.ImportPhase
import com.muedsa.js.runtime.module.ExperimentalModuleApi

/** ResolveExport 的结果；Namespace 对应 `export * as ns` 的命名空间导出。 */
internal sealed interface ResolveExportResult {
    data class Resolved(
        val module: ModuleRecord,
        val bindingName: String,
    ) : ResolveExportResult

    data class Namespace(
        val module: ModuleRecord,
        val phase: ImportPhase = ImportPhase.EAGER,
    ) : ResolveExportResult

    /** `import source x; export { x }`：解析为目标模块的 source 对象。 */
    data class Source(
        val module: ModuleRecord,
    ) : ResolveExportResult

    data object Ambiguous : ResolveExportResult
    data object NotFound : ResolveExportResult
}

/**
 * 静态导出解析（ES2024 ResolveExport / GetExportedNames）。
 *
 * 只做符号解析，不创建 namespace 对象、不读取绑定值。`export * as ns` 返回
 * [ResolveExportResult.Namespace]，由后续阶段创建 namespace exotic 对象。
 */
internal object ModuleExportResolver {

    /** 解析 [module] 的 [exportName]，循环通过 [resolveSet] 截断。 */
    fun resolveExport(
        module: ModuleRecord,
        exportName: String,
        resolveSet: MutableList<Pair<ModuleRecord, String>> = mutableListOf(),
    ): ResolveExportResult {
        if (resolveSet.any { it.first === module && it.second == exportName }) {
            return ResolveExportResult.NotFound
        }
        resolveSet.add(module to exportName)

        val explicit = module.program.exportEntries.firstOrNull { it.exportName == exportName }
        if (explicit != null) {
            return resolveExplicit(
                module = module,
                importName = explicit.importName,
                moduleRequest = explicit.moduleRequest,
                localName = explicit.localName,
                resolveSet = resolveSet,
            )
        }

        // `export *` 不导出 default；default 只能由显式条目提供。
        if (exportName == "default") return ResolveExportResult.NotFound

        var starResolution: ResolveExportResult? = null
        for (entry in module.program.exportEntries) {
            if (entry.exportName != null || entry.moduleRequest == null) continue
            val target = module.dependencyFor(entry.moduleRequest) ?: return ResolveExportResult.NotFound
            when (val result = resolveExport(target, exportName, resolveSet)) {
                ResolveExportResult.NotFound -> Unit
                ResolveExportResult.Ambiguous -> return ResolveExportResult.Ambiguous
                is ResolveExportResult.Resolved,
                is ResolveExportResult.Namespace,
                is ResolveExportResult.Source,
                -> {
                    if (starResolution == null) {
                        starResolution = result
                    } else if (starResolution != result) {
                        return ResolveExportResult.Ambiguous
                    }
                }
            }
        }
        return starResolution ?: ResolveExportResult.NotFound
    }

    private fun resolveExplicit(
        module: ModuleRecord,
        importName: String?,
        moduleRequest: String?,
        localName: String?,
        resolveSet: MutableList<Pair<ModuleRecord, String>>,
    ): ResolveExportResult {
        if (moduleRequest == null) {
            val local = localName ?: return ResolveExportResult.NotFound
            // `export { foo }` 若 foo 是 import 绑定，规范要求继续解析到原始导出，
            // 否则两个星号导出会错误地把同一绑定判成不同模块的歧义。
            val imported = module.program.importEntries.firstOrNull { it.localName == local }
            if (imported != null) {
                val target = module.dependencyFor(imported.moduleRequest)
                    ?: return ResolveExportResult.NotFound
                if (imported.phase == ImportPhase.SOURCE) {
                    return ResolveExportResult.Source(target)
                }
                if (imported.importName == "*") {
                    return ResolveExportResult.Namespace(target, imported.phase)
                }
                return resolveExport(target, imported.importName, resolveSet)
            }
            return ResolveExportResult.Resolved(module, local)
        }
        val target = module.dependencyFor(moduleRequest) ?: return ResolveExportResult.NotFound
        if (importName == "*") return ResolveExportResult.Namespace(target)
        return importName?.let { resolveExport(target, it, resolveSet) } ?: ResolveExportResult.NotFound
    }

    /** 返回排序、去重后的导出名集合；星号导出排除 default。 */
    fun exportedNames(module: ModuleRecord): List<String> =
        collectExportedNames(module, mutableSetOf()).sorted()

    private fun collectExportedNames(
        module: ModuleRecord,
        exportStarSet: MutableSet<ModuleRecord>,
    ): Set<String> {
        if (!exportStarSet.add(module)) return emptySet()
        val names = LinkedHashSet<String>()
        for (entry in module.program.exportEntries) {
            val exportName = entry.exportName
            if (exportName != null) {
                names.add(exportName)
                continue
            }
            val moduleRequest = entry.moduleRequest ?: continue
            val target = module.dependencyFor(moduleRequest) ?: continue
            for (name in collectExportedNames(target, exportStarSet)) {
                if (name != "default") names.add(name)
            }
        }
        return names
    }
}
