@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.ast.ImportPhase
import com.muedsa.js.runtime.DeclarativeEnvironment
import com.muedsa.js.runtime.Environment
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSValue

/**
 * 模块顶层环境。
 *
 * 本地 var/function/let/const/class 复用 [DeclarativeEnvironment] 的 Map 与 TDZ；
 * import 名是只读转发，不复制值，因此仍保持 live binding。模块环境自身是 var
 * 作用域，避免顶层 var/function 污染 globalThis。
 */
internal class ModuleEnvironment(
    parent: Environment? = null,
    val record: ModuleRecord,
) : DeclarativeEnvironment(parent = parent, isVarScope = true) {

    init {
        record.environment = this
    }

    override suspend fun hasBinding(name: String): Boolean =
        record.importBindings.containsKey(name) || super.hasBinding(name)

    override suspend fun hasOwnBinding(name: String): Boolean =
        record.importBindings.containsKey(name) || super.hasOwnBinding(name)

    override suspend fun getBinding(name: String): JSValue? {
        when (val imported = record.importBindings[name]) {
            is ModuleImportBinding.Resolved -> {
                val target = imported.target.environment
                    ?: throw JSException(
                        JSError("ReferenceError", "Module ${imported.target.id} is not instantiated"),
                    )
                return target.getBinding(imported.bindingName)
            }

            is ModuleImportBinding.Namespace -> {
                val namespace = if (imported.phase == ImportPhase.DEFER) {
                    imported.target.deferredNamespace
                } else {
                    imported.target.namespace
                }
                return namespace
                    ?: throw JSException(
                        JSError("ReferenceError", "Module namespace is not initialized: ${imported.target.id}"),
                    )
            }

            null -> Unit
        }
        return super.getBinding(name)
    }

    override suspend fun setBinding(name: String, value: JSValue, strict: Boolean): Boolean {
        if (record.importBindings.containsKey(name)) {
            throw JSException(JSError("TypeError", "Assignment to import binding '$name'."))
        }
        return super.setBinding(name, value, strict)
    }

    override fun deleteBinding(name: String): Boolean {
        if (record.importBindings.containsKey(name)) return false
        return super.deleteBinding(name)
    }

    /**
     * 非挂起地解析一个导出名的当前值，供 namespace 数据描述符使用。
     *
     * 这里重复走 ResolveExport（不缓存），保证 `export *` 与 live binding 的
     * 语义一致；值本身通过目标模块的本地/import 链实时读取。
     */
    internal fun resolveExportedValue(exportName: String): JSValue =
        when (val result = ModuleExportResolver.resolveExport(record, exportName)) {
            is ResolveExportResult.Resolved -> {
                result.module.environment?.resolveBindingValue(result.bindingName)
                    ?: throw JSException(
                        JSError(
                            "ReferenceError",
                            "Export '$exportName' is not initialized in ${result.module.id}",
                        ),
                    )
            }

            is ResolveExportResult.Namespace -> {
                val namespace = if (result.phase == ImportPhase.DEFER) {
                    result.module.deferredNamespace
                } else {
                    result.module.namespace
                }
                namespace
                    ?: throw JSException(
                        JSError("ReferenceError", "Module namespace is not initialized: ${result.module.id}"),
                    )
            }

            ResolveExportResult.Ambiguous -> throw JSException(
                JSError("SyntaxError", "Ambiguous export '$exportName'"),
            )

            ResolveExportResult.NotFound -> throw JSException(
                JSError("ReferenceError", "Export '$exportName' not found"),
            )
        }

    /** 按本地名读取：import 名递归转发，本地名走 DeclarativeEnvironment。 */
    internal fun resolveBindingValue(name: String): JSValue? {
        when (val imported = record.importBindings[name]) {
            is ModuleImportBinding.Resolved ->
                return imported.target.environment?.resolveBindingValue(imported.bindingName)

            is ModuleImportBinding.Namespace ->
                return if (imported.phase == ImportPhase.DEFER) {
                    imported.target.deferredNamespace
                } else {
                    imported.target.namespace
                }

            null -> Unit
        }
        return getLocalBindingValue(name)
    }
}
