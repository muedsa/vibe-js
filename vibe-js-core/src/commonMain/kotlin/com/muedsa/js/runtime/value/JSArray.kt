package com.muedsa.js.runtime.value

/**
 * JavaScript 数组。
 *
 * 设计说明：
 * - 使用 `MutableList<JSValue?>` 存储元素，null 表示数组空洞（hole）；
 * - length 是数组的特殊自有属性：写入 length 会截断/扩展数组；
 * - 索引属性的读取/写入由 getOwnProperty / defineOwnProperty 统一处理；
 * - 具体方法的实现放在内建模块（builtins/ArrayBuiltins）。
 */
class JSArray(
    private val elementList: MutableList<JSValue?> = mutableListOf(),
    prototype: JSObject? = null,
) : JSObject(prototype) {

    /** 元素列表（null = 空洞）。 */
    val elements: MutableList<JSValue?> get() = elementList

    /** 是否已冻结（元素与 length 不可修改）。 */
    var frozen: Boolean = false
        private set

    /** 是否已密封（不能新增/删除元素，但可修改已有元素）。 */
    var sealed: Boolean = false
        private set

    /** 当前长度。 */
    val length: Int get() = elementList.size

    /** 读取索引位置的值；越界或空洞返回 null。 */
    fun getElement(index: Int): JSValue? =
        if (index in elementList.indices) elementList[index] else null

    /** 写入索引位置；必要时扩展数组（填充空洞）。 */
    fun setElement(index: Int, value: JSValue) {
        if (index >= elementList.size) {
            repeat(index - elementList.size + 1) { elementList.add(null) }
        }
        elementList[index] = value
    }

    /** 设置 length：缩小则截断，扩大则填充空洞。 */
    fun setLength(newLength: Int) {
        if (newLength < 0) return
        if (newLength < elementList.size) {
            while (elementList.size > newLength) {
                elementList.removeAt(elementList.size - 1)
            }
        } else {
            repeat(newLength - elementList.size) { elementList.add(null) }
        }
    }

    /** 判断索引位置是否有实际元素（非空洞）。 */
    fun hasElement(index: Int): Boolean =
        index in elementList.indices && elementList[index] != null

    /** 冻结数组：禁止写入、删除与新增。 */
    fun freezeArray() {
        frozen = true
        sealed = true
        preventExtensions()
    }

    /** 密封数组：禁止新增与删除，但允许修改已有元素。 */
    fun sealArray() {
        sealed = true
        preventExtensions()
    }

    override fun getOwnProperty(key: PropertyKey): PropertyDescriptor? {
        if (key == PropertyKey.LENGTH) {
            return PropertyDescriptor.data(
                JSNumber(elementList.size.toDouble()),
                writable = !frozen,
                enumerable = false,
                configurable = false,
            )
        }
        val index = arrayIndex(key)
        if (index != null) {
            val value = getElement(index) ?: return null
            return PropertyDescriptor.data(
                value = value,
                writable = !frozen,
                enumerable = true,
                configurable = !sealed,
            )
        }
        return super.getOwnProperty(key)
    }

    override fun hasOwnProperty(key: PropertyKey): Boolean {
        if (key == PropertyKey.LENGTH) return true
        val index = arrayIndex(key)
        if (index != null) return hasElement(index)
        return super.hasOwnProperty(key)
    }

    override fun defineOwnProperty(key: PropertyKey, descriptor: PropertyDescriptor): Boolean {
        if (key == PropertyKey.LENGTH) {
            if (frozen) return false
            val newLength = descriptor.value
            if (newLength is JSNumber) {
                setLength(newLength.value.toInt())
                return true
            }
            return false
        }
        val index = arrayIndex(key)
        if (index != null) {
            if (descriptor.isAccessor) {
                // 阶段 B 仍不支持数组索引的访问器属性
                return false
            }
            val value = descriptor.value ?: return false
            val existing = hasElement(index)
            if (frozen && existing) return false
            if (sealed && !existing) return false
            if (!isExtensible() && !existing) return false
            setElement(index, value)
            return true
        }
        return super.defineOwnProperty(key, descriptor)
    }

    override fun deleteOwnProperty(key: PropertyKey): Boolean {
        if (key == PropertyKey.LENGTH) return false
        val index = arrayIndex(key)
        if (index != null) {
            if (sealed) return false
            if (!hasElement(index)) return true
            elementList[index] = null
            return true
        }
        return super.deleteOwnProperty(key)
    }

    override fun ownPropertyKeys(): List<PropertyKey> {
        val keys = mutableListOf<PropertyKey>()
        elementList.forEachIndexed { index, value ->
            if (value != null) keys.add(PropertyKey.Str(index.toString()))
        }
        keys.add(PropertyKey.LENGTH)
        // 数组上的非索引自有属性（例如自定义字段）
        super.ownPropertyKeys().forEach { key ->
            if (key != PropertyKey.LENGTH) keys.add(key)
        }
        return keys
    }

    override fun toPrimitiveString(): String =
        elementList.joinToString(",") { it?.toPrimitiveString() ?: "" }

    /**
     * 将属性键解析为数组索引；不是合法索引时返回 null。
     *
     * 合法索引：非负整数，且字符串形式无前导零（"0" 除外）。
     */
    private fun arrayIndex(key: PropertyKey): Int? {
        val text = (key as? PropertyKey.Str)?.value ?: return null
        if (text.isEmpty() || text.length > 10) return null
        if (text != "0" && text[0] == '0') return null
        var index = 0
        for (c in text) {
            if (c !in '0'..'9') return null
            index = index * 10 + (c - '0')
        }
        return index
    }
}
