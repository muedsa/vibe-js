@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

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
        getOwnProperty(key) != null

    override fun ownPropertyKeys(): List<PropertyKey> {
        val keys = ArrayList<PropertyKey>(record.exportedNames.size + 1)
        for (name in record.exportedNames) keys.add(PropertyKey.Str(name))
        keys.add(PropertyKey.Sym(symbolToStringTag))
        return keys
    }

    override fun defineOwnProperty(key: PropertyKey, descriptor: PropertyDescriptor): Boolean = false

    override fun deleteOwnProperty(key: PropertyKey): Boolean = false
}
