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
 * ES Module namespace exotic object。
 *
 * 空原型、不可扩展；导出名是实时数据属性（writable=true、enumerable=true、
 * configurable=false），读取时通过 [ModuleEnvironment.resolveExportedValue]
 * 重新解析并读取当前绑定。`Symbol.toStringTag` 为 "Module"。
 */
internal class JSModuleNamespace(
    private val record: ModuleRecord,
    private val symbolToStringTag: JSSymbol,
    private val interpreter: Interpreter,
) : JSObject(prototype = null) {

    init {
        preventExtensions()
    }

    private fun isExportName(name: String): Boolean = name in record.exportedNames

    private fun bindingValue(name: String): JSValue {
        val environment = record.environment
            ?: throw IllegalStateException("Module namespace used before declaration instantiation: ${record.id}")
        return environment.resolveExportedValue(name)
    }

    override fun getOwnProperty(key: PropertyKey): PropertyDescriptor? {
        if (key is PropertyKey.Sym && key.symbol === symbolToStringTag) {
            return PropertyDescriptor.data(
                value = JSString("Module"),
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

    override fun hasOwnProperty(key: PropertyKey): Boolean =
        (key is PropertyKey.Sym && key.symbol === symbolToStringTag) ||
            (key is PropertyKey.Str && isExportName(key.value))

    override fun ownPropertyKeys(): List<PropertyKey> {
        val keys = ArrayList<PropertyKey>(record.exportedNames.size + 1)
        for (name in record.exportedNames) keys.add(PropertyKey.Str(name))
        keys.add(PropertyKey.Sym(symbolToStringTag))
        return keys
    }

    /**
     * 规范 [[DefineOwnProperty]]：只允许不改变现有描述的请求。
     *
     * 不能直接复用普通对象的 ValidateAndApply，因为 namespace 的导出描述
     * 由内部槽实时生成，且不可扩展。
     */
    override fun defineOwnProperty(key: PropertyKey, descriptor: PropertyDescriptor): Boolean {
        if (key is PropertyKey.Sym) {
            if (key.symbol !== symbolToStringTag) return false
            if (descriptor.isAccessor) return false
            if (descriptor.hasConfigurable && descriptor.configurable) return false
            if (descriptor.hasEnumerable && descriptor.enumerable) return false
            if (descriptor.hasWritable && descriptor.writable) return false
            if (descriptor.hasValue) {
                val value = descriptor.value
                if (value !is JSString || value.value != "Module") return false
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

    /** 规范 [[Delete]]：导出名与 @@toStringTag 不可删，其余键返回 true。 */
    override fun deleteOwnProperty(key: PropertyKey): Boolean = when (key) {
        is PropertyKey.Str -> !isExportName(key.value)
        is PropertyKey.Sym -> key.symbol !== symbolToStringTag
        else -> true
    }
}
