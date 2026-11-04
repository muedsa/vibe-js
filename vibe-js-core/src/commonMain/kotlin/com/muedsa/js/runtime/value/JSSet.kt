package com.muedsa.js.runtime.value

/**
 * Set 对象。
 *
 * 存储结构与 [JSMap] 一致：插入顺序列表 + SameValueZero 索引 + O(1) 有效计数，
 * 删除位置留墓碑，并在没有活跃迭代器时压缩回收。
 *
 * 迭代语义（C1 约定）：
 * - 遍历期间新增的值会被访问；删除的值被跳过；重删重加作为新记录再次访问；
 * - `clear()` 推进逻辑起点并释放槽位，旧迭代器恢复时跳到新起点继续。
 */
open class JSSet(
    prototype: JSObject?,
) : JSObject(prototype) {

    /** 插入顺序存储；null 表示已删除的墓碑。 */
    private val storage: MutableList<JSValue?> = mutableListOf()

    /** SameValueZero 值索引：值 → 物理下标。 */
    private val index: MutableMap<CollectionKey, Int> = HashMap()

    /** 有效值数量（不含墓碑）。 */
    private var liveCount: Int = 0

    /** 尚未结束的迭代器数量；大于 0 时禁止压缩。 */
    private var activeIterations: Int = 0

    /**
     * 当前存储起点对应的逻辑槽位。clear 推进起点并清除旧槽位及引用，旧游标
     * 只需跳过已清空区间即可继续遍历新值，避免为了兼容迭代永久保留墓碑。
     */
    internal var iterationStartIndex: Long = 0L
        private set

    /** 实时迭代的逻辑终点，随新增值增长。 */
    internal val iterationEndIndex: Long get() = iterationStartIndex + storage.size

    /** 当前有效值数量（不含墓碑）；O(1)。 */
    val liveSize: Int get() = liveCount

    /** 按逻辑槽位读取；物理下标与查找/删除接口仍使用原有的 Int 索引。 */
    internal fun valueAtIterationIndex(index: Long): JSValue? {
        val offset = index - iterationStartIndex
        return if (offset >= 0 && offset < storage.size) storage[offset.toInt()] else null
    }

    /** 标记一个迭代器/forEach 开始；结束前禁止压缩以保持游标有效。 */
    internal fun beginIteration() {
        activeIterations++
    }

    /** 标记迭代结束，并尝试回收迭代期间产生的墓碑。 */
    internal fun endIteration() {
        if (activeIterations > 0) activeIterations--
        maybeCompact()
    }

    /** 是否包含指定值。 */
    fun containsValue(value: JSValue): Boolean = index.containsKey(CollectionKey(value))

    /**
     * 添加值：已存在则忽略（Set 语义），否则追加到末尾。
     *
     * 规范要求 Set 中的 `-0` 归一化为 `+0`，这里统一处理。
     *
     * @return 是否真的新增了值
     */
    fun addValue(value: JSValue): Boolean {
        val normalized = normalizeCollectionKey(value)
        if (index.containsKey(CollectionKey(normalized))) return false
        index[CollectionKey(normalized)] = storage.size
        storage.add(normalized)
        liveCount++
        return true
    }

    /** 删除指定值；返回是否真的删除了值。 */
    fun deleteValue(value: JSValue): Boolean {
        val position = index.remove(CollectionKey(value)) ?: return false
        storage[position] = null
        liveCount--
        maybeCompact()
        return true
    }

    /** 物理移除所有满足条件的值（仅用于新建结果集合，避免累积墓碑）。 */
    fun removeValuesMatching(predicate: (JSValue) -> Boolean) {
        // 先收集命中的值再逐个删除：删除会改动索引，不能在遍历 storage 时直接改
        val matched = storage.filterNotNull().filter(predicate)
        for (value in matched) {
            deleteValue(value)
        }
    }

    /** 当前有效值的快照（保持插入顺序）。 */
    fun liveValues(): List<JSValue> = storage.filterNotNull()

    /** 清除全部值引用与墓碑，同时保留进行中遍历所使用的逻辑位置。 */
    fun clearValues() {
        iterationStartIndex = iterationEndIndex
        storage.clear()
        index.clear()
        liveCount = 0
    }

    /** 存储槽数量（含墓碑），供诊断与测试观察压缩效果。 */
    fun storageSize(): Int = storage.size

    /** 在没有活跃迭代器且墓碑占比过高时压缩存储。 */
    private fun maybeCompact() {
        if (activeIterations > 0) return
        // 空集合不保留任何槽位：此时没有游标，可以安全清空
        if (liveCount == 0) {
            if (storage.isNotEmpty()) {
                storage.clear()
                index.clear()
                iterationStartIndex = 0L
            }
            return
        }
        val tombstones = storage.size - liveCount
        if (tombstones <= COMPACTION_MIN_TOMBSTONES) return
        if (tombstones <= liveCount) return
        compact()
    }

    private fun compact() {
        val compacted = ArrayList<JSValue>(liveCount)
        for (value in storage) {
            if (value != null) compacted.add(value)
        }
        storage.clear()
        storage.addAll(compacted)
        index.clear()
        for (position in storage.indices) {
            val value = storage[position] ?: continue
            index[CollectionKey(value)] = position
        }
        // 没有活跃迭代器时才可以归零；此时不存在引用旧逻辑编号的游标
        iterationStartIndex = 0L
    }

    companion object {
        /** 少于该数量的墓碑不值得压缩（避免小集合反复复制）。 */
        private const val COMPACTION_MIN_TOMBSTONES = 64
    }
}
