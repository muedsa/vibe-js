package com.muedsa.js.runtime.value

/**
 * Map 对象。
 *
 * 使用插入顺序的键值对列表存储；键比较使用 SameValueZero（在内建模块中实现）。
 * 删除条目时保留 null 墓碑，保证 forEach/迭代器在遍历期间的游标稳定：
 * 新增条目追加到末尾，重删重加的键作为新记录再次被访问。
 */
open class JSMap(
    prototype: JSObject?,
) : JSObject(prototype) {

    /** 插入顺序存储；null 表示已删除的墓碑。 */
    private val storage: MutableList<Pair<JSValue, JSValue>?> = mutableListOf()

    /**
     * 当前物理存储起点对应的逻辑槽位。clear 清除全部槽位及引用，但向前推进起点，
     * 已有迭代器可跳过被清空的区间，继续访问随后追加的条目，无需登记存活游标。
     */
    internal var iterationStartIndex: Long = 0L
        private set

    /** 实时迭代的逻辑终点；新增条目会延长，clear 不会让旧游标越过新条目。 */
    internal val iterationEndIndex: Long get() = iterationStartIndex + storage.size

    /** 按逻辑槽位读取；调用方先将游标推进至当前起点，再读取剩余条目。 */
    internal fun entryAtIterationIndex(index: Long): Pair<JSValue, JSValue>? {
        val offset = index - iterationStartIndex
        return if (offset >= 0 && offset < storage.size) storage[offset.toInt()] else null
    }

    /** 当前有效条目数（不含墓碑）。 */
    val liveSize: Int
        get() = storage.count { it != null }

    /** 存储槽数量（含墓碑），供实时迭代判断边界。 */
    fun storageSize(): Int = storage.size

    /** 读取指定槽位的有效条目；墓碑或越界返回 null。 */
    fun entryAt(index: Int): Pair<JSValue, JSValue>? = storage.getOrNull(index)

    /** 追加新条目（保留插入顺序）。 */
    fun appendEntry(key: JSValue, value: JSValue) {
        storage.add(key to value)
    }

    /** 覆盖已有槽位的键值。 */
    fun replaceEntry(index: Int, key: JSValue, value: JSValue) {
        storage[index] = key to value
    }

    /** 将指定槽位标记为墓碑。 */
    fun removeEntryAt(index: Int) {
        storage[index] = null
    }

    /** 返回首个满足条件的有效条目下标；不存在返回 -1。 */
    fun findEntryIndex(predicate: (Pair<JSValue, JSValue>) -> Boolean): Int {
        storage.forEachIndexed { index, entry ->
            if (entry != null && predicate(entry)) return index
        }
        return -1
    }

    /** 当前有效条目的快照（保持插入顺序）。 */
    fun liveEntries(): List<Pair<JSValue, JSValue>> = storage.filterNotNull()

    /** 清除全部条目引用与墓碑；逻辑位置继续前进以保留进行中的遍历。 */
    fun clearEntries() {
        iterationStartIndex = iterationEndIndex
        storage.clear()
    }
}
