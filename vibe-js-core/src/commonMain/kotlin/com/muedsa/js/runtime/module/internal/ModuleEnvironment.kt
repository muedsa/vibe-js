@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

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
                return imported.target.namespace
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
}
