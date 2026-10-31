package com.muedsa.js.runtime.value

/**
 * JavaScript 普通对象。
 *
 * 属性以 [PropertyKey] 为键、[PropertyDescriptor] 为值存储：
 * - 支持数据属性与访问器属性（getter/setter 的调用由解释器执行）；
 * - 支持原型链（[prototype] 可修改，为后续 Object.setPrototypeOf 预留）；
 * - 属性顺序使用 LinkedHashMap 保留插入顺序。
 *
 * 注意：这里只负责"存储与查询"，不负责调用 getter/setter。
 * 读取/写入语义（OrdinaryGet/OrdinarySet）在解释器中实现。
 */
open class JSObject(
    var prototype: JSObject? = null,
) : JSValue() {

    /** 自有属性表（按插入顺序）。 */
    private val ownProperties: MutableMap<PropertyKey, PropertyDescriptor> = LinkedHashMap()

    /** 是否可扩展（能否新增属性）。 */
    var extensible: Boolean = true
        private set

    /**
     * 解释器内部状态槽。
     *
     * 用于承载规范中的内部槽（如迭代器的 [[Iterated]] / [[NextIndex]]），
     * 不参与属性枚举，也不会暴露给脚本代码。
     */
    var internalState: Any? = null

    /** 是否具有 [[ErrorData]] 内部槽（供 Error.isError 判断）。 */
    var isErrorData: Boolean = false

    override val isPrimitive: Boolean = false

    override fun toPrimitiveBoolean(): Boolean = true

    override fun toPrimitiveNumber(): Double = Double.NaN

    override fun toPrimitiveString(): String = "[object Object]"

    /** 获取自有属性描述符，不存在返回 null。 */
    open fun getOwnProperty(key: PropertyKey): PropertyDescriptor? = ownProperties[key]

    /** 是否拥有指定自有属性（不查原型链）。 */
    open fun hasOwnProperty(key: PropertyKey): Boolean = ownProperties.containsKey(key)

    /**
     * 获取全部自有属性键。
     *
     * 顺序遵循规范：数组索引升序，然后是其他字符串（插入顺序），最后是 Symbol。
     */
    open fun ownPropertyKeys(): List<PropertyKey> {
        val indexKeys = ArrayList<Pair<Long, PropertyKey>>()
        val stringKeys = ArrayList<PropertyKey>()
        val symbolKeys = ArrayList<PropertyKey>()
        for (key in ownProperties.keys) {
            if (key is PropertyKey.Str) {
                val index = canonicalArrayIndex(key.value)
                if (index != null) indexKeys.add(index to key) else stringKeys.add(key)
            } else {
                symbolKeys.add(key)
            }
        }
        indexKeys.sortBy { it.first }
        val result = ArrayList<PropertyKey>(ownProperties.size)
        for ((_, key) in indexKeys) result.add(key)
        result.addAll(stringKeys)
        result.addAll(symbolKeys)
        return result
    }

    /** 解析规范数组索引（0..2^32-2，无前导零）。 */
    private fun canonicalArrayIndex(text: String): Long? {
        if (text.isEmpty() || text.length > 10) return null
        if (text != "0" && text[0] == '0') return null
        var value = 0L
        for (c in text) {
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        return if (value <= 4294967294L) value else null
    }

    /** 获取可枚举的自有属性键（可选定只取数据属性）。 */
    fun enumerableOwnPropertyKeys(): List<PropertyKey> =
        ownProperties.entries.filter { it.value.enumerable }.map { it.key }

    /**
     * 定义/覆盖自有属性。
     *
     * 阶段 A 采用简化语义：直接写入描述符，不做可配置性/可写性冲突检查。
     * 阶段 B 会按 OrdinaryDefineOwnProperty 补齐校验。
     */
    open fun defineOwnProperty(key: PropertyKey, descriptor: PropertyDescriptor): Boolean {
        val existing = ownProperties[key]
        if (existing == null && !extensible) return false
        ownProperties[key] = descriptor
        return true
    }

    /**
     * 删除自有属性。
     *
     * @return 删除成功或属性不存在返回 true；不可配置属性返回 false
     */
    open fun deleteOwnProperty(key: PropertyKey): Boolean {
        val descriptor = ownProperties[key] ?: return true
        if (!descriptor.configurable) return false
        ownProperties.remove(key)
        return true
    }

    /** 内部使用：无视属性标志移除自有属性（用于创建后修正内建语义）。 */
    fun removeOwnPropertyIgnoringAttributes(key: PropertyKey) {
        ownProperties.remove(key)
    }

    /** 便捷方法：定义数据属性。 */
    fun defineDataProperty(        key: PropertyKey,
        value: JSValue,
        writable: Boolean = true,
        enumerable: Boolean = false,
        configurable: Boolean = true,
    ): JSObject {
        defineOwnProperty(key, PropertyDescriptor.data(value, writable, enumerable, configurable))
        return this
    }

    /** 便捷方法：定义访问器属性。 */
    fun defineAccessorProperty(
        key: PropertyKey,
        get: JSValue?,
        set: JSValue?,
        enumerable: Boolean = false,
        configurable: Boolean = true,
    ): JSObject {
        defineOwnProperty(key, PropertyDescriptor.accessor(get, set, enumerable, configurable))
        return this
    }

    /** 读取自有数据属性的值；属性不存在或为访问器时返回 null。 */
    fun getOwnDataValue(key: PropertyKey): JSValue? {
        val descriptor = ownProperties[key] ?: return null
        return if (descriptor.isData) descriptor.value else null
    }

    /** 设置自有数据属性的值；不存在时创建可写/可枚举/可配置的数据属性。 */
    fun setOwnDataValue(key: PropertyKey, value: JSValue) {
        val descriptor = ownProperties[key]
        if (descriptor != null && descriptor.isData) {
            descriptor.value = value
        } else if (descriptor == null) {
            ownProperties[key] = PropertyDescriptor.data(value)
        }
    }

    /** 是否可扩展。 */
    fun isExtensible(): Boolean = extensible

    /** 禁止新增属性。 */
    fun preventExtensions() {
        extensible = false
    }

    /** 原型链上是否存在该属性（不触发 getter）。 */
    fun hasProperty(key: PropertyKey): Boolean {
        var current: JSObject? = this
        while (current != null) {
            if (current.hasOwnProperty(key)) return true
            current = current.prototype
        }
        return false
    }
}
