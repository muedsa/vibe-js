@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSSymbol
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyDescriptor
import com.muedsa.js.runtime.value.PropertyKey

/**
 * `import defer` 的延迟模块 namespace。
 *
 * 它和 [JSModuleNamespace] 共享导出名/描述符规则，但具有独立的身份和
 * `[[Deferred]]` 状态。属性访问是否触发求值由触发矩阵决定，不能简单
 * 复用普通 namespace 的 eager 读取。
 */
internal class JSDeferredModuleNamespace(
    private val record: ModuleRecord,
    private val symbolToStringTag: JSSymbol,
    private val interpreter: Interpreter,
) : JSObject(prototype = null) {

    init {
        preventExtensions()
    }

    private fun isExportName(name: String): Boolean = name in record.exportedNames

    /** Symbol 键和 `then` 不触发求值；`then` 是避免 Promise 误同化的特殊键。 */
    private fun isSymbolLikeKey(key: PropertyKey): Boolean =
        key is PropertyKey.Sym || (key is PropertyKey.Str && key.value == "then")

    private fun ensureEvaluated() {
        if (record.status == ModuleStatus.EVALUATED) return
        ModuleEvaluator(interpreter).evaluateSync(record)
    }

    private fun bindingValue(name: String): JSValue {
        val environment = record.environment
            ?: throw IllegalStateException("Deferred namespace used before declaration instantiation: ${record.id}")
        return environment.resolveExportedValue(name)
    }

    override fun getOwnProperty(key: PropertyKey): PropertyDescriptor? {
        if (!isSymbolLikeKey(key)) ensureEvaluated()
        if (key is PropertyKey.Sym && key.symbol === symbolToStringTag) {
            return PropertyDescriptor.data(
                value = JSString("Deferred Module"),
                writable = false,
                enumerable = false,
                configurable = false,
            )
        }
        if (key is PropertyKey.Str && isExportName(key.value)) {
            return PropertyDescriptor.data(
                value = bindingValue(key.value),
                writable = true,
                enumerable = true,
                configurable = false,
            )
        }
        return null
    }

    override fun hasOwnProperty(key: PropertyKey): Boolean {
        if (!isSymbolLikeKey(key)) ensureEvaluated()
        return (key is PropertyKey.Sym && key.symbol === symbolToStringTag) ||
            (key is PropertyKey.Str && isExportName(key.value))
    }

    override fun ownPropertyKeys(): List<PropertyKey> {
        ensureEvaluated()
        val keys = ArrayList<PropertyKey>(record.exportedNames.size + 1)
        for (name in record.exportedNames) keys.add(PropertyKey.Str(name))
        keys.add(PropertyKey.Sym(symbolToStringTag))
        return keys
    }

    override fun defineOwnProperty(key: PropertyKey, descriptor: PropertyDescriptor): Boolean {
        if (!isSymbolLikeKey(key)) ensureEvaluated()
        if (key is PropertyKey.Sym) {
            if (key.symbol !== symbolToStringTag) return false
            if (descriptor.isAccessor) return false
            if (descriptor.hasConfigurable && descriptor.configurable) return false
            if (descriptor.hasEnumerable && descriptor.enumerable) return false
            if (descriptor.hasWritable && descriptor.writable) return false
            if (descriptor.hasValue) {
                val value = descriptor.value
                if (value !is JSString || value.value != "Deferred Module") return false
            }
            return true
        }
        if (key !is PropertyKey.Str || !isExportName(key.value)) return false
        if (descriptor.isAccessor) return false
        if (descriptor.hasConfigurable && descriptor.configurable) return false
        if (descriptor.hasEnumerable && !descriptor.enumerable) return false
        if (descriptor.hasWritable && !descriptor.writable) return false
        if (descriptor.hasValue && !interpreter.sameValue(descriptor.value!!, bindingValue(key.value))) {
            return false
        }
        return true
    }

    override fun deleteOwnProperty(key: PropertyKey): Boolean {
        if (!isSymbolLikeKey(key)) ensureEvaluated()
        return when (key) {
            is PropertyKey.Str -> !isExportName(key.value)
            is PropertyKey.Sym -> key.symbol !== symbolToStringTag
            else -> true
        }
    }
}
