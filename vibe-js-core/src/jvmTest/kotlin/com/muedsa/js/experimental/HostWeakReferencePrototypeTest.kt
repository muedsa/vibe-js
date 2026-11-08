package com.muedsa.js.experimental

import com.muedsa.js.runtime.value.JSMap
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.PropertyKey
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * 【实验性 · M2 可行性验证】Route C 的 JVM 行为验证。
 *
 * 这些用例证明两件事：
 * 1. 宿主弱引用确实可以在键对象不可达后回收条目（真实 JVM GC，而不是模拟根集合）；
 * 2. 这种方案无法实现 ephemeron 语义：值强引用键时条目必然泄漏，与
 *    `java.util.WeakHashMap` 的已知限制一致。
 *
 * 测试产生的计时数据仅用于指示实现成本，不是严谨基准。
 */
class HostWeakReferencePrototypeTest {

    /**
     * 最小真实回收场景：键对象只被弱引用持有，GC 之后条目可被 expunge 删除。
     *
     * 键在独立函数中创建，调用方只保留 [WeakReference]，避免测试栈帧意外
     * 持有强引用导致 GC 无法回收。
     */
    @Test
    fun `键对象不可达后弱引用条目可在 GC 后被删除`() {
        val map = HostWeakReferenceWeakMap()
        val reference = createDetachedEntry(map)
        assertEquals(1, map.slotCount, "写入后应存在一个条目")

        assertTrue(
            awaitCollected(reference),
            "JVM 在 5 秒内仍未回收不可达的键对象，无法验证弱引用语义",
        )
        // Reference 被清除不等于条目被删除：需要检查点显式清理。
        assertEquals(1, map.slotCount, "清理检查点之前条目仍占位")
        assertEquals(1, map.expunge(), "expunge 应删除键已回收的条目")
        assertEquals(0, map.slotCount)
    }

    /**
     * 键仍然强可达时，GC 不能删除条目；基础读写/覆盖/删除语义与 WeakMap 一致。
     */
    @Test
    fun `键可达时弱引用条目保留且基础语义正确`() {
        val map = HostWeakReferenceWeakMap()
        val first = JSObject()
        val second = JSObject()

        assertFalse(map.has(first))
        map.set(first, JSNumber(1.0))
        assertTrue(map.has(first))
        assertEquals(1.0, assertIs<JSNumber>(map.get(first)).value)

        // 同键覆盖不新增槽位。
        map.set(first, JSNumber(2.0))
        assertEquals(1, map.slotCount)
        assertEquals(2.0, assertIs<JSNumber>(map.get(first)).value)

        // 对象键按身份比较：内容相同的另一个对象是不同键。
        map.set(second, JSString("other"))
        assertEquals(2, map.slotCount)
        assertNull(map.get(JSObject()), "对象键按身份比较，内容相同的新对象不是同一个键")
        assertEquals("other", assertIs<JSString>(map.get(second)).value)

        System.gc()
        assertEquals(0, map.expunge(), "键仍强可达时不应清理任何条目")
        assertEquals(2, map.slotCount)

        assertTrue(map.delete(first))
        assertFalse(map.has(first))
        assertEquals(1, map.slotCount)
        assertFalse(map.delete(first), "重复 delete 应返回 false")
    }

    /**
     * 【已知限制】值强引用键时，弱引用路线无法回收条目。
     *
     * 这条用例不依赖 GC 时机：键经 value → key 的强引用链始终可达，
     * Reference 不可能被清除，因此条目必然泄漏。真正的 ephemeron 语义
     * 只能由 GC 层实现，JVM 没有暴露该能力。
     */
    @Test
    fun `值强引用键时弱引用路线无法回收条目`() {
        val map = HostWeakReferenceWeakMap()
        val reference = createSelfReferencingEntry(map)

        // 键仍被 value 强引用，属于"可达"，不能声称是 GC 没跑。
        assertNotNull(reference.get(), "值引用键时键必须仍可达")
        assertEquals(0, map.expunge(), "可达的键不能被清理")
        assertEquals(1, map.slotCount, "ephemeron 场景下条目会泄漏，这是 Route C 的硬限制")
    }

    /**
     * 指示性成本：比较弱引用表与现有 [com.muedsa.js.runtime.value.JSMap] 的
     * set/get/expunge 耗时。输出会被写入 JUnit XML 的 system-out，供可行性报告引用。
     */
    @Test
    fun `弱引用表相对强 Map 的指示性操作成本`() {
        val count = 20_000
        val keys = List(count) { JSObject() }
        val weak = HostWeakReferenceWeakMap()
        val strong = JSMap(prototype = null)

        val weakSet = measureTime { keys.forEach { weak.set(it, JSNumber(1.0)) } }
        val strongSet = measureTime { keys.forEach { strong.setEntry(it, JSNumber(1.0)) } }
        val weakGet = measureTime { keys.forEach { check(weak.get(it) != null) } }
        val strongGet = measureTime { keys.forEach { check(strong.getEntry(it) != null) } }
        val weakExpunge = measureTime { check(weak.expunge() == 0) }

        assertEquals(count, weak.slotCount)
        assertEquals(count, strong.liveSize)
        println(
            "M2-PROTO host-weak(count=$count): set=$weakSet get=$weakGet expunge=$weakExpunge; " +
                "JSMap: set=$strongSet get=$strongGet",
        )
    }

    /** 在独立栈帧中创建键并写入条目，返回键的弱引用；调用方不持有键。 */
    private fun createDetachedEntry(map: HostWeakReferenceWeakMap): WeakReference<JSObject> {
        val key = JSObject()
        map.set(key, JSNumber(42.0))
        return WeakReference(key)
    }

    /** 值对象强引用键，用于证明 ephemeron 缺口。 */
    private fun createSelfReferencingEntry(map: HostWeakReferenceWeakMap): WeakReference<JSObject> {
        val key = JSObject()
        val value = JSObject().defineDataProperty(PropertyKey.Str("key"), key)
        map.set(key, value)
        return WeakReference(key)
    }

    /**
     * 等待 JVM 回收弱引用目标。
     *
     * `System.gc()` 只是建议，因此循环触发并短暂等待；同时分配少量垃圾，
     * 促使分代/并发收集器完成 Reference 清理。5 秒仍失败视为环境不支持。
     */
    private fun awaitCollected(reference: WeakReference<*>, timeoutMillis: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (reference.get() == null) return true
            System.gc()
            Thread.sleep(20)
            garbage = ByteArray(8 * 1024)
        }
        return reference.get() == null
    }

    companion object {
        /** 制造分配压力用；不持有任何被测对象。 */
        private var garbage: Any? = null
    }
}
