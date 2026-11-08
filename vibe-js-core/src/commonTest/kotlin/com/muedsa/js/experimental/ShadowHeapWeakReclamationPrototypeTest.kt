package com.muedsa.js.experimental

import com.muedsa.js.runtime.DeclarativeEnvironment
import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSMap
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSSymbol
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 【实验性 · M2 可行性验证】Route A（影子堆 + 显式根 + 标记清除）的行为验证。
 *
 * 这些用例只使用测试源码中的原型，不修改生产代码，因此不会影响任何现有基线。
 * 它们回答两个问题：
 * 1. 在"能枚举根与引用图"的前提下，ephemeron 语义（含值引用键、链式弱键、
 *    强 Map 可达等）是否可以被正确实现；
 * 2. 缺少哪些可达性信息（闭包环境）会让模型产生误回收——这正是 Route A
 *    不能直接落地生产的原因。
 */
class ShadowHeapWeakReclamationPrototypeTest {

    /**
     * 最小验收场景：键对象失去唯一的显式根后，WeakMap 条目在下次回收时被删除，
     * 键与值对象同时从影子堆清除。
     */
    @Test
    fun `键对象失去显式根后 WeakMap 条目可被回收`() {
        val heap = ExperimentalWeakReclamationHeap()
        val map = ExperimentalWeakMap(heap)
        val key = heap.newObject()
        val value = heap.newObject()
        map.set(key, value)

        // 键仍是根：条目与值都必须保留。
        heap.addRoot(key)
        val retained = heap.collect()
        assertEquals(1, map.liveSize, "键可达时条目必须保留")
        assertEquals(0, retained.reclaimedEntries, "键可达时不应回收条目")
        assertTrue(map.has(key))
        assertEquals(value, map.get(key), "回收前 get 应返回原值")
        assertTrue(heap.isManaged(value), "值随键可达而被标记")

        // 移除唯一的根：键不可达 → 条目删除 → 值也不再被条目追踪。
        heap.removeRoot(key)
        val reclaimed = heap.collect()
        assertEquals(0, map.liveSize, "键不可达后条目必须被回收")
        assertEquals(1, reclaimed.reclaimedEntries, "应恰好回收一个条目")
        assertFalse(map.has(key), "回收后 has 必须为 false")
        assertNull(map.get(key), "回收后 get 必须为 undefined（null 占位）")
        assertFalse(heap.isManaged(key), "键对象应从影子堆清除")
        assertFalse(heap.isManaged(value), "值对象应随条目一起清除")
        assertEquals(2, reclaimed.reclaimedObjects, "键与值两个对象都应被清除")
        assertEquals(2, reclaimed.scannedObjects)
    }

    /**
     * 键作为根时的基础读写语义：同键覆盖不新增条目、delete 后立即不可见。
     */
    @Test
    fun `键作为显式根时条目保留并保持 WeakMap 读写语义`() {
        val heap = ExperimentalWeakReclamationHeap()
        val map = ExperimentalWeakMap(heap)
        val key = heap.newObject()
        heap.addRoot(key)

        map.set(key, JSNumber(1.0))
        assertEquals(1.0, assertIs<JSNumber>(map.get(key)).value)
        map.set(key, JSNumber(2.0))
        assertEquals(1, map.liveSize, "SameValueZero 同键覆盖不应新增条目")
        assertEquals(2.0, assertIs<JSNumber>(map.get(key)).value)

        heap.collect()
        assertTrue(map.has(key), "键仍在根中时回收不能删除条目")

        assertTrue(map.delete(key), "delete 已存在的键应返回 true")
        assertFalse(map.has(key))
        assertEquals(0, map.liveSize)
        // 显式删除后回收器不应再次回收它。
        assertEquals(0, heap.collect().reclaimedEntries)
    }

    /**
     * 引用图追踪：键只被普通对象的属性持有时仍然可达，清空属性后才回收。
     *
     * 这条用例区分了"只标记显式根"和"追踪对象引用图"两种实现：前者会误回收。
     */
    @Test
    fun `键通过普通对象属性可达时条目保留并在属性删除后回收`() {
        val heap = ExperimentalWeakReclamationHeap()
        val map = ExperimentalWeakMap(heap)
        val key = heap.newObject()
        val holder = heap.newObject().defineDataProperty(stringKey("key"), key)
        map.set(key, JSUndefined)

        heap.addRoot(holder)
        heap.collect()
        assertTrue(map.has(key), "holder.key 的可达路径应让键保持存活")

        assertTrue(holder.deleteOwnProperty(stringKey("key")), "测试属性应可配置")
        heap.collect()
        assertFalse(map.has(key), "属性删除后键不再可达，条目应被回收")
    }

    /**
     * 引用图追踪：键作为强 Map 的值被持有时同样可达；强 Map 删除后才回收。
     */
    @Test
    fun `键通过强 Map 的值可达时条目保留`() {
        val heap = ExperimentalWeakReclamationHeap()
        val map = ExperimentalWeakMap(heap)
        val key = heap.newObject()
        val strongMap = heap.register(JSMap(prototype = null)) as JSMap
        strongMap.setEntry(JSString("holder"), key)
        map.set(key, JSUndefined)

        heap.addRoot(strongMap)
        heap.collect()
        assertTrue(map.has(key), "强 Map 的值引用应让键保持存活")

        assertTrue(strongMap.deleteEntry(JSString("holder")))
        heap.collect()
        assertFalse(map.has(key), "强 Map 删除键后条目应被回收")
    }

    /**
     * ephemeron 的核心性质：值强引用键时，条目本身不能反过来让键存活。
     *
     * 这是 Java `WeakHashMap` 做不到的语义（它会被 value → key 的强引用链泄漏），
     * 也是"宿主弱引用 + 逻辑删除"路线（Route C）在 JVM 上的已知缺口。
     */
    @Test
    fun `值引用键时仍按 ephemeron 语义回收条目`() {
        val heap = ExperimentalWeakReclamationHeap()
        val map = ExperimentalWeakMap(heap)
        val key = heap.newObject()
        // 值对象反过来强引用键，模拟 wm.set(k, { key: k })。
        val value = heap.newObject().defineDataProperty(stringKey("key"), key)
        map.set(key, value)

        // 没有根：键未标记，因此其值也不会被追踪，value → key 的边不能救回键。
        val report = heap.collect()
        assertEquals(1, report.reclaimedEntries)
        assertEquals(0, map.liveSize)
        assertFalse(map.has(key))
        assertFalse(heap.isManaged(key), "键不能因为值引用它而存活")
        assertFalse(heap.isManaged(value), "未标记的值对象应被清除")
    }

    /**
     * 链式 ephemeron 必须迭代到不动点。
     *
     * 构造顺序刻意让"被引用链才可达的键"排在前面：
     * - 条目顺序：[k2 → v2]（先注册）、[k1 → v1]（后注册）；
     * - v1 的属性引用 k2；
     * - 根只有 k1。
     * 单遍顺序扫描会在处理 k2 时还没标记它，从而误删第二条条目；只有反复扫描
     * 直到没有新标记，才能保留 k2 与 v2。
     */
    @Test
    fun `链式 ephemeron 需要不动点才能保留可达值`() {
        val heap = ExperimentalWeakReclamationHeap()
        val map1 = ExperimentalWeakMap(heap)
        val map2 = ExperimentalWeakMap(heap)
        val key1 = heap.newObject()
        val key2 = heap.newObject()
        val value2 = heap.newObject()
        val value1 = heap.newObject().defineDataProperty(stringKey("next"), key2)
        // 先注册 key2 的条目，确保单遍扫描会在 key2 被标记之前处理它。
        map2.set(key2, value2)
        map1.set(key1, value1)

        heap.addRoot(key1)
        heap.collect()
        assertEquals(1, map1.liveSize, "key1 可达，第一条目保留")
        assertEquals(1, map2.liveSize, "value1 → key2 让第二条目也保留")
        assertTrue(heap.isManaged(key2), "k2 应通过 v1 的引用被标记")
        assertTrue(heap.isManaged(value2), "v2 应通过 k2 的标记被追踪")

        heap.removeRoot(key1)
        heap.collect()
        assertEquals(0, map1.liveSize)
        assertEquals(0, map2.liveSize, "k1 不可达后整条链都应回收")
        assertFalse(heap.isManaged(key2))
        assertFalse(heap.isManaged(value2))
    }

    /**
     * 回收结果必须同步到真实 [com.muedsa.js.runtime.value.JSWeakMap] 存储：
     * 不是只改测试结构，而是真的删除条目并释放物理槽位。
     */
    @Test
    fun `回收后真实 WeakMap 存储同步删除条目`() {
        val heap = ExperimentalWeakReclamationHeap()
        val map = ExperimentalWeakMap(heap)
        val key = heap.newObject()
        map.set(key, JSUndefined)

        assertTrue(map.target.containsKey(key), "写入后真实 WeakMap 应包含键")
        assertEquals(1, map.target.storageSize())

        heap.collect()
        assertFalse(map.target.containsKey(key), "回收后真实 WeakMap 不应再包含键")
        assertEquals(0, map.target.liveSize)
        assertEquals(0, map.target.storageSize(), "唯一有效条目删除后应释放物理槽位")
    }

    /**
     * 非注册 Symbol 也是合法弱键（CanBeHeldWeakly），同样参与根标记与回收。
     */
    @Test
    fun `非注册 Symbol 键同样参与显式根回收`() {
        val heap = ExperimentalWeakReclamationHeap()
        val map = ExperimentalWeakMap(heap)
        val key = JSSymbol("weak-key")
        map.set(key, JSNumber(1.0))

        heap.addRoot(key)
        heap.collect()
        assertTrue(map.has(key), "Symbol 在根中时条目保留")
        assertEquals(1.0, assertIs<JSNumber>(map.get(key)).value)

        heap.removeRoot(key)
        heap.collect()
        assertFalse(map.has(key), "Symbol 失去根后条目应被回收")
    }

    /**
     * WeakSet 与 WeakMap 共用 ephemeron 机制：成员失去根后同样被删除。
     */
    @Test
    fun `WeakSet 成员失去根后可被回收`() {
        val heap = ExperimentalWeakReclamationHeap()
        val set = ExperimentalWeakSet(heap)
        val member = heap.newObject()

        set.add(member)
        heap.addRoot(member)
        heap.collect()
        assertTrue(set.has(member), "成员可达时 WeakSet 应保留")
        assertEquals(1, set.liveSize)

        heap.removeRoot(member)
        heap.collect()
        assertFalse(set.has(member), "成员不可达后 WeakSet 应删除")
        assertEquals(0, set.liveSize)
    }

    /**
     * 【已知边界】闭包环境中的键无法被追踪，原型会误回收。
     *
     * 这个用例故意断言原型的错误行为，用来量化 Route A 落地生产前必须补齐的
     * 可达性信息：`DeclarativeEnvironment.bindings` 是私有字段，`JSFunction.closure`
     * 只能拿到 Environment 引用，无法枚举其绑定；真实引擎里通过闭包仍可访问的键，
     * 在模型里却被判为不可达。生产实现必须为 Environment 增加引用枚举接口，
     * 并保证所有活跃环境都是根，否则会把仍可达的对象提前回收。
     */
    @Test
    fun `已知边界_闭包绑定中的键无法被追踪时原型会提前回收`() {
        val heap = ExperimentalWeakReclamationHeap()
        val map = ExperimentalWeakMap(heap)
        val key = heap.newObject()

        // 构造 f = function() { ... }，闭包环境里有一个名为 key 的绑定指向被测键。
        val closure = DeclarativeEnvironment()
        closure.createVarBinding("key", key)
        val holder = JSFunction(
            name = "holder",
            params = emptyList(),
            body = null,
            closure = closure,
            strict = false,
            prototype = null,
            functionPrototypeObject = null,
        )
        heap.register(holder)
        heap.addRoot(holder)
        map.set(key, JSUndefined)

        val report = heap.collect()

        // 真实语义：通过 holder 的闭包仍能读取 key，条目必须保留。
        // 原型语义：闭包绑定不可枚举 → key 未标记 → 条目被删除（误回收）。
        assertFalse(
            map.has(key),
            "原型未追踪闭包绑定，因此产生了误回收；这是 Route A 的已知缺陷而非期望行为",
        )
        assertEquals(1, report.reclaimedEntries)
    }
}
