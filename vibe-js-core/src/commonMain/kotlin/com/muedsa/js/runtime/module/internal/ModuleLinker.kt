@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.ast.ImportPhase
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

                if (entry.phase == ImportPhase.SOURCE) {
                    record.importBindings[entry.localName] =
                        ModuleImportBinding.Source(target)
                    continue
                }

                if (entry.importName == "*") {
                    record.importBindings[entry.localName] =
                        ModuleImportBinding.Namespace(target, entry.phase)
                    continue
                }

                when (val result = ModuleExportResolver.resolveExport(target, entry.importName)) {
                    is ResolveExportResult.Resolved -> {
                        record.importBindings[entry.localName] =
                            ModuleImportBinding.Resolved(result.module, result.bindingName)
                    }

                    is ResolveExportResult.Namespace -> {
                        record.importBindings[entry.localName] =
                            ModuleImportBinding.Namespace(result.module, result.phase)
                    }

                    is ResolveExportResult.Source -> {
                        record.importBindings[entry.localName] =
                            ModuleImportBinding.Source(result.module)
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

            // ModuleDeclarationInstantiation 会先解析所有间接导出；循环或缺失必须
            // 在求值前报 SyntaxError，不能让 `$DONOTEVALUATE()` 进入模块体。
            for (entry in record.program.exportEntries) {
                val importName = entry.importName ?: continue
                if (entry.moduleRequest == null || importName == "*") continue
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
                when (ModuleExportResolver.resolveExport(target, importName)) {
                    ResolveExportResult.NotFound -> throw ModuleLinkException(
                        kind = ModuleLinkErrorKind.MISSING_EXPORT,
                        specifier = entry.moduleRequest,
                        referrer = record.id,
                        moduleId = target.id,
                        exportName = entry.exportName,
                        sourceName = record.source.sourceName,
                        range = entry.range,
                        message = "Indirect export '${entry.exportName}' cannot be resolved in ${target.id}",
                    )

                    ResolveExportResult.Ambiguous -> throw ModuleLinkException(
                        kind = ModuleLinkErrorKind.AMBIGUOUS_EXPORT,
                        specifier = entry.moduleRequest,
                        referrer = record.id,
                        moduleId = target.id,
                        exportName = entry.exportName,
                        sourceName = record.source.sourceName,
                        range = entry.range,
                        message = "Indirect export '${entry.exportName}' is ambiguous in ${target.id}",
                    )

                    else -> Unit
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
