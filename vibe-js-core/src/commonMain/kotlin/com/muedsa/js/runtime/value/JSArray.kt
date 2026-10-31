package com.muedsa.js.runtime.value

/**
 * JavaScript 数组（数组异质对象）。
 *
 * 存储模型：
 * - 数组索引属性（0..2^32-2）以稀疏描述符表保存，长度可达 2^32-1 而不分配连续内存；
 * - 其余属性（包括 "4294967295" 这类非索引数字键）沿用 [JSObject] 的属性表；
 * - length 是内部状态，按 ES5 15.4.5.1 的规则维护可写性与收缩行为。
 *
 * 具体方法实现位于内建模块（builtins/ArrayBuiltins）。
 */
open class JSArray(
    initialElements: List<JSValue?> = emptyList(),
    prototype: JSObject? = null,
) : JSObject(prototype) {

    companion object {
        /** 数组长度的最大值：2^32 - 1。 */
        const val MAX_LENGTH: Long = 4294967295L

        /** 数组索引的最大值：2^32 - 2。 */
        const val MAX_INDEX: Long = 4294967294L
    }

    /** 数组索引属性（稀疏，按插入顺序保存）。 */
    private val indexedProperties = LinkedHashMap<Long, PropertyDescriptor>()

    /** 逻辑长度。 */
    private var arrayLength: Long = initialElements.size.toLong()

    /** length 的 [[Writable]]。 */
    private var lengthWritable: Boolean = true

    /** 是否已冻结。 */
    var frozen: Boolean = false
        private set

    /** 是否已密封。 */
    var sealed: Boolean = false
        private set

    /** length 是否可写（冻结后恒为 false）。 */
    val isLengthWritable: Boolean get() = lengthWritable

    init {
        initialElements.forEachIndexed { index, value ->
            if (value != null) {
                indexedProperties[index.toLong()] = PropertyDescriptor.data(value)
            }
        }
    }

    /** 当前逻辑长度（完整 0..2^32-1 范围）。 */
    val lengthLong: Long get() = arrayLength

    /** 当前逻辑长度的 Int 视图；超出 Int 范围时饱和，供常规循环使用。 */
    val length: Int get() = if (arrayLength > Int.MAX_VALUE) Int.MAX_VALUE else arrayLength.toInt()

    /** 读取索引位置的数据值；不存在、空洞或访问器属性返回 null。 */
    fun getElement(index: Long): JSValue? {
        val descriptor = indexedProperties[index] ?: return null
        return if (descriptor.isData) descriptor.value else null
    }

    /** [getElement] 的 Int 重载，便于常规循环使用。 */
    fun getElement(index: Int): JSValue? = getElement(index.toLong())

    /** 判断索引位置是否存在属性（包括访问器属性）。 */
    fun hasElement(index: Long): Boolean = indexedProperties.containsKey(index)

    /** [hasElement] 的 Int 重载。 */
    fun hasElement(index: Int): Boolean = hasElement(index.toLong())

    /**
     * 内部快速写入（跳过挂起的访问器调用）。
     *
     * @return 写入成功返回 true；只读、访问器或不可扩展时返回 false
     */
    fun setElement(index: Long, value: JSValue): Boolean {
        if (index < 0 || index > MAX_INDEX) return false
        val existing = indexedProperties[index]
        if (existing != null) {
            if (existing.isAccessor || !existing.writable) return false
            existing.value = value
            return true
        }
        if (!isExtensible()) return false
        if (index >= arrayLength) {
            if (!lengthWritable) return false
            arrayLength = index + 1
        }
        indexedProperties[index] = PropertyDescriptor.data(value)
        return true
    }

    /** [setElement] 的 Int 重载。 */
    fun setElement(index: Int, value: JSValue): Boolean = setElement(index.toLong(), value)

    /** 删除索引属性（不考虑可配置性），用于方法内部搬移元素。 */
    fun removeElement(index: Long) {
        indexedProperties.remove(index)
    }

    /** [removeElement] 的 Int 重载。 */
    fun removeElement(index: Int) = removeElement(index.toLong())

    /** 清空全部索引属性（长度保持不变）。 */
    fun clearElements() {
        indexedProperties.clear()
    }

    /** 已存在索引的快照（升序），用于遍历/排序。 */
    fun elementIndices(): List<Long> = indexedProperties.keys.sorted()

    /** 已存在索引的数据值快照（升序，访问器忽略），用于需要密集值的场景。 */
    fun presentValues(): List<JSValue> {
        val result = ArrayList<JSValue>(indexedProperties.size)
        for (index in elementIndices()) {
            val descriptor = indexedProperties[index] ?: continue
            if (descriptor.isData) descriptor.value?.let { result.add(it) }
        }
        return result
    }

    /** 内部直接设置长度：收缩时删除越界索引（不检查 length 可写性）。 */
    fun setLength(newLength: Long) {
        if (newLength < 0 || newLength > MAX_LENGTH) return
        if (newLength < arrayLength) {
            val removedKeys = indexedProperties.keys.filter { it >= newLength }
            removedKeys.forEach { indexedProperties.remove(it) }
        }
        arrayLength = newLength
    }

    /**
     * 按 ES5 15.4.5.1 的 ArraySetLength 语义更新 length。
     *
     * @param newLength 已通过 ToUint32 校验的新长度
     * @param writable 描述符中的 [[Writable]]（调用方已完成缺省合并）
     * @return 是否成功；失败时已按规范回退 length
     */
    fun defineLength(newLength: Long, writable: Boolean): Boolean {
        if (newLength == arrayLength) {
            if (!writable) lengthWritable = false
            return true
        }
        if (newLength > arrayLength) {
            arrayLength = newLength
            if (!writable) lengthWritable = false
            return true
        }
        // 收缩长度要求 length 当前可写
        if (!lengthWritable) return false
        var failedIndex = -1L
        for (index in indexedProperties.keys.sortedDescending()) {
            if (index < newLength) break
            val descriptor = indexedProperties[index] ?: continue
            if (!descriptor.configurable) {
                failedIndex = index
                break
            }
            indexedProperties.remove(index)
        }
        if (failedIndex >= 0) {
            // 规范：删除失败时 length 停在失败索引 + 1
            arrayLength = failedIndex + 1
            if (!writable) lengthWritable = false
            return false
        }
        arrayLength = newLength
        if (!writable) lengthWritable = false
        return true
    }

    override fun getOwnProperty(key: PropertyKey): PropertyDescriptor? {
        if (key == PropertyKey.LENGTH) {
            return PropertyDescriptor.data(
                JSNumber(arrayLength.toDouble()),
                writable = lengthWritable,
                enumerable = false,
                configurable = false,
            )
        }
        val index = arrayIndex(key) ?: return super.getOwnProperty(key)
        return indexedProperties[index]?.copy()
    }

    override fun hasOwnProperty(key: PropertyKey): Boolean {
        if (key == PropertyKey.LENGTH) return true
        val index = arrayIndex(key) ?: return super.hasOwnProperty(key)
        return indexedProperties.containsKey(index)
    }

    override fun defineOwnProperty(key: PropertyKey, descriptor: PropertyDescriptor): Boolean {
        if (key == PropertyKey.LENGTH) {
            // 完整的 ToNumber/ToUint32 转换由 Interpreter.defineOwnProperty 完成；
            // 这里只处理已是合法长度的内部调用与索引路径
            val value = descriptor.value
            val newLength = when {
                value == null -> arrayLength
                value is JSNumber -> value.value.toArrayLengthOrNull() ?: return false
                else -> return false
            }
            if (descriptor.isAccessor) return false
            if (descriptor.enumerable || descriptor.configurable) return false
            return defineLength(newLength, descriptor.writable)
        }
        val index = arrayIndex(key) ?: return super.defineOwnProperty(key, descriptor)
        val oldLength = arrayLength
        if (!lengthWritable && index >= oldLength) return false
        if (!defineIndexProperty(index, descriptor)) return false
        if (index >= oldLength) {
            arrayLength = index + 1
        }
        return true
    }

    /** 索引属性的 OrdinaryDefineOwnProperty（含不可配置属性的不变量）。 */
    private fun defineIndexProperty(index: Long, descriptor: PropertyDescriptor): Boolean {
        val existing = indexedProperties[index]
        if (existing == null) {
            if (!isExtensible()) return false
            indexedProperties[index] = if (descriptor.isAccessor) {
                PropertyDescriptor.accessor(
                    get = descriptor.get,
                    set = descriptor.set,
                    enumerable = descriptor.enumerable,
                    configurable = descriptor.configurable,
                )
            } else {
                PropertyDescriptor.data(
                    value = descriptor.value ?: JSUndefined,
                    writable = descriptor.writable,
                    enumerable = descriptor.enumerable,
                    configurable = descriptor.configurable,
                )
            }
            return true
        }
        return applyDescriptor(index, existing, descriptor)
    }

    /** 把描述符合并进已有属性；不允许时返回 false（ES5 8.12.9）。 */
    private fun applyDescriptor(
        index: Long,
        existing: PropertyDescriptor,
        descriptor: PropertyDescriptor,
    ): Boolean {
        val requestsAccessor = descriptor.isAccessor
        val requestsData = !descriptor.isAccessor && descriptor.value != null
        if (!existing.configurable) {
            if (descriptor.configurable) return false
            if (descriptor.enumerable != existing.enumerable) return false
            if (requestsAccessor || requestsData) {
                if (requestsAccessor != existing.isAccessor) return false
                if (requestsAccessor) {
                    if (!sameValue(descriptor.get, existing.get)) return false
                    if (!sameValue(descriptor.set, existing.set)) return false
                } else if (!existing.writable) {
                    if (descriptor.writable) return false
                    if (!sameValue(descriptor.value, existing.value)) return false
                }
            }
        }

        val typeChanges = requestsAccessor != existing.isAccessor &&
            (requestsAccessor || requestsData)
        if (typeChanges) {
            indexedProperties[index] = if (requestsAccessor) {
                PropertyDescriptor.accessor(
                    get = descriptor.get,
                    set = descriptor.set,
                    enumerable = descriptor.enumerable,
                    configurable = descriptor.configurable,
                )
            } else {
                PropertyDescriptor.data(
                    value = descriptor.value ?: JSUndefined,
                    writable = descriptor.writable,
                    enumerable = descriptor.enumerable,
                    configurable = descriptor.configurable,
                )
            }
            return true
        }

        if (requestsAccessor) {
            existing.get = descriptor.get
            existing.set = descriptor.set
            existing.accessor = true
        } else if (requestsData) {
            existing.value = descriptor.value
            existing.writable = descriptor.writable
        }
        existing.enumerable = descriptor.enumerable
        existing.configurable = descriptor.configurable
        return true
    }

    override fun deleteOwnProperty(key: PropertyKey): Boolean {
        if (key == PropertyKey.LENGTH) return false
        val index = arrayIndex(key) ?: return super.deleteOwnProperty(key)
        val descriptor = indexedProperties[index] ?: return true
        if (!descriptor.configurable) return false
        indexedProperties.remove(index)
        return true
    }

    override fun ownPropertyKeys(): List<PropertyKey> {
        val keys = ArrayList<PropertyKey>(indexedProperties.size + 1)
        for (index in indexedProperties.keys.sorted()) {
            keys.add(PropertyKey.Str(index.toString()))
        }
        keys.add(PropertyKey.LENGTH)
        for (key in super.ownPropertyKeys()) {
            if (key != PropertyKey.LENGTH) keys.add(key)
        }
        return keys
    }

    override fun toPrimitiveString(): String {
        val builder = StringBuilder()
        // 超大稀疏数组拼接会产生数十亿分隔符，这里限制实际遍历范围
        val limit = if (arrayLength > 1_000_000L) 1_000_000L else arrayLength
        for (index in 0 until limit) {
            if (index > 0) builder.append(',')
            val element = getElement(index)
            if (element != null && element !is JSNull && element !is JSUndefined) {
                builder.append(element.toPrimitiveString())
            }
        }
        return builder.toString()
    }

    /** 冻结数组：长度与索引全部不可写、不可配置且不可扩展。 */
    fun freezeArray() {
        frozen = true
        sealed = true
        lengthWritable = false
        preventExtensions()
        for (descriptor in indexedProperties.values) {
            descriptor.writable = false
            descriptor.configurable = false
        }
    }

    /** 密封数组：索引不可配置、不可扩展，但可修改已有数据值。 */
    fun sealArray() {
        sealed = true
        preventExtensions()
        for (descriptor in indexedProperties.values) {
            descriptor.configurable = false
        }
    }

    /** 将属性键解析为数组索引；越界或形如 "01" 的字符串返回 null。 */
    private fun arrayIndex(key: PropertyKey): Long? {
        val text = (key as? PropertyKey.Str)?.value ?: return null
        if (text.isEmpty() || text.length > 10) return null
        if (text != "0" && text[0] == '0') return null
        var value = 0L
        for (c in text) {
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        return if (value <= MAX_INDEX) value else null
    }

    private fun sameValue(left: JSValue?, right: JSValue?): Boolean {
        if (left == null || right == null) return left == right
        return when {
            left is JSUndefined -> right is JSUndefined
            left is JSNull -> right is JSNull
            left is JSNumber && right is JSNumber -> {
                if (left.value.isNaN() && right.value.isNaN()) true
                else if (left.value == 0.0 && right.value == 0.0) {
                    1.0 / left.value == 1.0 / right.value
                } else {
                    left.value == right.value
                }
            }
            left is JSString && right is JSString -> left.value == right.value
            left is JSBoolean && right is JSBoolean -> left.value == right.value
            left is JSSymbol && right is JSSymbol -> left === right
            else -> left === right
        }
    }

    private fun Double.toArrayLengthOrNull(): Long? {
        if (isNaN() || this < 0.0 || this > MAX_LENGTH.toDouble()) return null
        if (this != kotlin.math.floor(this)) return null
        return toLong()
    }
}
