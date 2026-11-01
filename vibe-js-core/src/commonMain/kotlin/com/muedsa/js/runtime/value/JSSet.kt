package com.muedsa.js.runtime.value

/**
 * Set 对象。
 *
 * 使用插入顺序的值列表存储；值比较使用 SameValueZero（在内建模块中实现）。
 * 删除值时保留 null 墓碑，保证 forEach/迭代器在遍历期间的游标稳定：
 * 新增值追加到末尾，重删重加的值作为新记录再次被访问。
 */
open class JSSet(
    prototype: JSObject?,
) : JSObject(prototype) {

    /** 插入顺序存储；null 表示已删除的墓碑。 */
    private val storage: MutableList<JSValue?> = mutableListOf()

    /** 当前有效值数量（不含墓碑）。 */
    val liveSize: Int
        get() = storage.count { it != null }

    /** 存储槽数量（含墓碑），供实时迭代判断边界。 */
    fun storageSize(): Int = storage.size

    /** 读取指定槽位的有效值；墓碑或越界返回 null。 */
    fun valueAt(index: Int): JSValue? = storage.getOrNull(index)

    /** 追加新值（保留插入顺序）。 */
    fun appendValue(value: JSValue) {
        storage.add(value)
    }

    /** 将指定槽位标记为墓碑。 */
    fun removeValueAt(index: Int) {
        storage[index] = null
    }

    /** 物理移除所有满足条件的有效值（仅用于新建结果集合，避免累积墓碑）。 */
    fun removeValuesMatching(predicate: (JSValue) -> Boolean) {
        val remaining = storage.filterNotNull().filterNot(predicate)
        storage.clear()
        storage.addAll(remaining)
    }

    /** 返回首个满足条件的有效值下标；不存在返回 -1。 */
    fun findValueIndex(predicate: (JSValue) -> Boolean): Int {
        storage.forEachIndexed { index, value ->
            if (value != null && predicate(value)) return index
        }
        return -1
    }

    /** 当前有效值的快照（保持插入顺序）。 */
    fun liveValues(): List<JSValue> = storage.filterNotNull()

    /** 清空全部值与墓碑。 */
    fun clearValues() {
        storage.clear()
    }
}
