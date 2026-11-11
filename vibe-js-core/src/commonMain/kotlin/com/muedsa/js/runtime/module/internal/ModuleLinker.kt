@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleLinkErrorKind
import com.muedsa.js.runtime.module.ModuleLinkException

/**
 * 静态链接：为每条 import 解析目标导出、拒绝 TLA、填充 namespace 导出名。
 *
 * 只建立绑定元数据，不创建 ModuleEnvironment、不执行模块体。失败快速失败，
 * 不把异常留给求值阶段。
 */
internal object ModuleLinker {

    fun link(graph: ModuleGraph): ModuleGraph {
        for (record in graph.records.values) {
            // 动态 import 复用的记录可能已经链接/求值；重复链接会把状态回退并导致重复求值。
            if (record.status != ModuleStatus.NEW) continue
            if (record.program.hasTopLevelAwait) {
                throw ModuleLinkException(
                    kind = ModuleLinkErrorKind.UNSUPPORTED_TLA,
                    moduleId = record.id,
                    sourceName = record.source.sourceName,
                    range = record.program.range,
                    message = "Top-level await is not supported by the module MVP: ${record.id}",
                )
            }
            for (entry in record.program.importEntries) {
                val target = record.dependencyFor(entry.moduleRequest)
                    ?: throw ModuleLinkException(
                        kind = ModuleLinkErrorKind.NOT_FOUND,
                        specifier = entry.moduleRequest,
                        referrer = record.id,
                        moduleId = record.id,
                        sourceName = record.source.sourceName,
                        range = entry.range,
                        message = "Module dependency was not loaded: ${entry.moduleRequest}",
                    )

                if (entry.importName == "*") {
                    record.importBindings[entry.localName] = ModuleImportBinding.Namespace(target)
                    continue
                }

                when (val result = ModuleExportResolver.resolveExport(target, entry.importName)) {
                    is ResolveExportResult.Resolved -> {
                        record.importBindings[entry.localName] =
                            ModuleImportBinding.Resolved(result.module, result.bindingName)
                    }

                    is ResolveExportResult.Namespace -> {
                        record.importBindings[entry.localName] =
                            ModuleImportBinding.Namespace(result.module)
                    }

                    ResolveExportResult.Ambiguous -> throw ModuleLinkException(
                        kind = ModuleLinkErrorKind.AMBIGUOUS_EXPORT,
                        specifier = entry.moduleRequest,
                        referrer = record.id,
                        moduleId = target.id,
                        exportName = entry.importName,
                        sourceName = record.source.sourceName,
                        range = entry.range,
                        message = "Ambiguous export '${entry.importName}' requested by ${record.id}",
                    )

                    ResolveExportResult.NotFound -> throw ModuleLinkException(
                        kind = ModuleLinkErrorKind.MISSING_EXPORT,
                        specifier = entry.moduleRequest,
                        referrer = record.id,
                        moduleId = target.id,
                        exportName = entry.importName,
                        sourceName = record.source.sourceName,
                        range = entry.range,
                        message = "Missing export '${entry.importName}' in ${target.id} requested by ${record.id}",
                    )
                }
            }
            // GetExportedNames 会保留星号导出的候选名；namespace 只暴露能唯一解析的名字，
            // 因而同名的多个 `export *` 来源不应出现在 namespace 键中。
            record.exportedNames = ModuleExportResolver.exportedNames(record).filter { name ->
                ModuleExportResolver.resolveExport(record, name) !is ResolveExportResult.Ambiguous
            }
            record.status = ModuleStatus.LINKED
        }
        return graph
    }
}
