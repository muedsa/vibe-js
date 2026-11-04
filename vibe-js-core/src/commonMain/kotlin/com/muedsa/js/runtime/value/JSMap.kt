package com.muedsa.js.runtime.value

/**
 * Map 对象。
 *
 * 存储结构：
 * - [storage] 按插入顺序保存键值对，删除位置留 `null` 墓碑；
 * - [index] 是 SameValueZero 键索引（键 → 物理下标），使 get/set/has/delete 为均摊 O(1)；
 * - [liveCount] 维护有效条目数，`size` 读取为 O(1)；
 * - 墓碑在**没有活跃迭代器**时压缩回收（[maybeCompact]）。
 *
 * 迭代语义（C1 约定，不能破坏）：
 * - 迭代器持有逻辑位置而不是物理下标；遍历期间新增的条目会被访问；
 * - 删除的条目被跳过，重删重加作为新记录再次访问；
 * - `clear()` 推进逻辑起点并释放全部槽位，旧迭代器恢复时跳到新起点继续。
 *
 * 迭代器会阻止压缩：压缩会重新编号物理下标，只有确认没有活跃迭代器时才安全。
 * 因此"创建后既不耗尽也不丢弃"的迭代器会让该集合保留墓碑——这与主流引擎
 * 中迭代器持有底层表的生命周期一致，属于已记录的边界。
 */
open class JSMap(
    prototype: JSObject?,
) : JSObject(prototype) {

    /** 插入顺序存储；null 表示已删除的墓碑。 */
    private val storage: MutableList<Pair<JSValue, JSValue>?> = mutableListOf()

    /** SameValueZero 键索引：键 → 物理下标。 */
    private val index: MutableMap<CollectionKey, Int> = HashMap()

    /** 有效条目数（不含墓碑）。 */
    private var liveCount: Int = 0

    /** 尚未结束的迭代器数量；大于 0 时禁止压缩。 */
    private var activeIterations: Int = 0

    /**
     * 当前物理存储起点对应的逻辑槽位。clear 清除全部槽位及引用，但向前推进起点，
     * 已有迭代器可跳过被清空的区间，继续访问随后追加的条目，无需登记存活游标。
     */
    internal var iterationStartIndex: Long = 0L
        private set

    /** 实时迭代的逻辑终点；新增条目会延长，clear 不会让旧游标越过新条目。 */
    internal val iterationEndIndex: Long get() = iterationStartIndex + storage.size

    /** 当前有效条目数（不含墓碑）；O(1)。 */
    val liveSize: Int get() = liveCount

    /** 按逻辑槽位读取；调用方先将游标推进至当前起点，再读取剩余条目。 */
    internal fun entryAtIterationIndex(index: Long): Pair<JSValue, JSValue>? {
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

    /** 读取指定键的条目（键值对保留插入顺序中的位置）。 */
    fun getEntry(key: JSValue): Pair<JSValue, JSValue>? {
        val position = index[CollectionKey(key)] ?: return null
        return storage.getOrNull(position)
    }

    /** 是否包含指定键。 */
    fun containsKey(key: JSValue): Boolean = index.containsKey(CollectionKey(key))

    /**
     * 写入条目：已存在的键只更新值（保留原键与位置），否则追加到末尾。
     *
     * 规范要求 Map 中的 `-0` 键归一化为 `+0`，这里统一处理，调用方无需重复规范化。
     */
    fun setEntry(key: JSValue, value: JSValue) {
        val normalizedKey = normalizeCollectionKey(key)
        val existingPosition = index[CollectionKey(normalizedKey)]
        if (existingPosition != null) {
            val existing = storage.getOrNull(existingPosition) ?: return
            // 保留原键：SameValueZero 相等意味着可观察的键没有变化
            storage[existingPosition] = existing.first to value
            return
        }
        index[CollectionKey(normalizedKey)] = storage.size
        storage.add(normalizedKey to value)
        liveCount++
    }

    /** 删除指定键；返回是否真的删除了条目。 */
    fun deleteEntry(key: JSValue): Boolean {
        val position = index.remove(CollectionKey(key)) ?: return false
        storage[position] = null
        liveCount--
        maybeCompact()
        return true
    }

    /** 清除全部条目引用与墓碑；逻辑位置继续前进以保留进行中的遍历。 */
    fun clearEntries() {
        iterationStartIndex = iterationEndIndex
        storage.clear()
        index.clear()
        liveCount = 0
    }

    /** 当前有效条目的快照（保持插入顺序）。 */
    fun liveEntries(): List<Pair<JSValue, JSValue>> = storage.filterNotNull()

    /** 存储槽数量（含墓碑），供诊断与测试观察压缩效果。 */
    fun storageSize(): Int = storage.size

    /**
     * 在没有活跃迭代器且墓碑占比过高时压缩存储。
     *
     * 压缩会重新编号物理下标并把逻辑起点归零，因此必须确认没有游标在使用旧编号。
     */
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
        // 只有墓碑多于有效条目时才值得压缩，避免频繁复制
        if (tombstones <= liveCount) return
        compact()
    }

    private fun compact() {
        val compacted = ArrayList<Pair<JSValue, JSValue>>(liveCount)
        for (entry in storage) {
            if (entry != null) compacted.add(entry)
        }
        storage.clear()
        storage.addAll(compacted)
        index.clear()
        for (position in storage.indices) {
            val entry = storage[position] ?: continue
            index[CollectionKey(entry.first)] = position
        }
        // 没有活跃迭代器时才可以归零；此时不存在引用旧逻辑编号的游标
        iterationStartIndex = 0L
    }

    companion object {
        /** 少于该数量的墓碑不值得压缩（避免小集合反复复制）。 */
        private const val COMPACTION_MIN_TOMBSTONES = 64
    }
}
