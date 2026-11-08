package com.muedsa.js.experimental

import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSValue
import java.lang.ref.WeakReference

/**
 * 【实验性 · M2 可行性验证】Route C 的 JVM 原型：宿主弱引用 + 逻辑删除。
 *
 * 与 commonTest 的影子堆原型不同，这里不模拟可达性，而是直接使用 JVM GC：
 * [WeakReference] 不会阻止键对象被回收，[expunge] 在引用被清除后删除条目。
 * 它验证的是"如果宿主提供真实弱引用，WeakMap 可以在不改 JS 语义的前提下回收条目"，
 * 以及这种方案在 JVM 上的两个硬限制：
 *
 * 1. **ephemeron 语义不可实现**：值对象如果强引用键（`wm.set(k, { k })`），
 *    键会经 value → key 的强引用链保持存活，弱引用永远不会被清除，条目泄漏。
 *    `java.util.WeakHashMap` 有完全相同的限制；真正的 ephemeron 需要 GC 支持，
 *    JVM 没有等价机制（JS 引擎的原生 WeakMap 才有）。
 * 2. **回收时机不可控**：Reference 被清除后条目仍在表里，必须由检查点调用
 *    [expunge] 才能删除；真实实现需要在事件循环安全点批量清理，并控制单次
 *    扫描预算，避免每次 WeakMap 操作都做全表扫描。
 *
 * 本类只支持对象键（[JSObject]），与规范允许的非注册 Symbol 键不同；
 * JVM 的 WeakReference 可以弱引用 Symbol，但 JS 目标的原生 WeakRef 不支持
 * Symbol，跨平台实现必须为 Symbol 设计单独的降级路径（详见可行性报告）。
 *
 * 仅存在于 jvmTest，不进入 commonMain、不改变现有 [com.muedsa.js.runtime.value.JSWeakMap]。
 */
internal class HostWeakReferenceWeakMap {

    /** 一个条目：键只被弱引用，值保持强引用（与 JVM WeakHashMap 相同）。 */
    private class Slot(
        val key: WeakReference<JSObject>,
        var value: JSValue,
    )

    /** 以键的身份哈希分桶；桶内用 `===` 做身份比较，处理哈希碰撞。 */
    private val buckets = HashMap<Int, MutableList<Slot>>()

    /** 条目总数（包含 Reference 已清除但尚未 [expunge] 的条目）。 */
    val slotCount: Int get() = buckets.values.sumOf { it.size }

    /** 写入条目：同键覆盖值，不新增槽位。 */
    fun set(key: JSObject, value: JSValue) {
        val bucket = buckets.getOrPut(System.identityHashCode(key)) { ArrayList(1) }
        for (slot in bucket) {
            if (slot.key.get() === key) {
                slot.value = value
                return
            }
        }
        bucket.add(Slot(WeakReference(key), value))
    }

    /** 读取条目；键已被回收或从未写入时返回 null。 */
    fun get(key: JSObject): JSValue? = findSlot(key)?.value

    /** 是否存在键。 */
    fun has(key: JSObject): Boolean = findSlot(key) != null

    /** 删除条目；返回是否真的删除了。 */
    fun delete(key: JSObject): Boolean {
        val bucket = buckets[System.identityHashCode(key)] ?: return false
        val iterator = bucket.iterator()
        while (iterator.hasNext()) {
            val slot = iterator.next()
            val referent = slot.key.get()
            if (referent == null) {
                // 顺手清理已失效的槽位，避免 dead entry 长期占位。
                iterator.remove()
                continue
            }
            if (referent === key) {
                iterator.remove()
                return true
            }
        }
        return false
    }

    /**
     * 删除所有键已被 GC 回收的条目。
     *
     * 这是"检查点"式清理：宿主可以在事件循环空闲/每轮微任务结束时调用，
     * 避免把清理成本摊进每次 WeakMap 操作。
     *
     * @return 被删除的条目数
     */
    fun expunge(): Int {
        var removed = 0
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val bucket = iterator.next().value
            val before = bucket.size
            bucket.removeAll { it.key.get() == null }
            removed += before - bucket.size
            if (bucket.isEmpty()) iterator.remove()
        }
        return removed
    }

    /** 按身份查找仍然存活的条目；遇到已清除的引用顺手移除。 */
    private fun findSlot(key: JSObject): Slot? {
        val bucket = buckets[System.identityHashCode(key)] ?: return null
        val iterator = bucket.iterator()
        while (iterator.hasNext()) {
            val slot = iterator.next()
            val referent = slot.key.get()
            if (referent == null) {
                iterator.remove()
                continue
            }
            if (referent === key) return slot
        }
        return null
    }
}
