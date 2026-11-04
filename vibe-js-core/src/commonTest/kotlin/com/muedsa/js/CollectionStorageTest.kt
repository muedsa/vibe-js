package com.muedsa.js

import com.muedsa.js.runtime.value.JSMap
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSSet
import com.muedsa.js.runtime.value.JSString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Map/Set 存储结构与键语义的回归测试（M1）。
 *
 * 覆盖：
 * - SameValueZero 键语义（NaN、-0/+0、对象身份、字符串按值）；
 * - `size` 由有效计数维护，不再依赖遍历计数；
 * - 墓碑在无活跃迭代器时压缩回收；
 * - 活跃迭代器期间禁止压缩，保证实时游标（C1 语义）不被破坏。
 */
class CollectionStorageTest {

    @Test
    fun `Map 的键按 SameValueZero 比较`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean(
                """
                    var m = new Map();
                    m.set(NaN, 1);
                    m.set(-0, 'zero');
                    var a = {}, b = {};
                    m.set(a, 'a'); m.set(b, 'b');
                    m.set('s', 'str');
                    m.get(NaN) === 1 &&
                        m.get(NaN) === m.get(NaN) &&
                        m.get(0) === 'zero' &&
                        m.get(+0) === 'zero' &&
                        m.has(NaN) && m.has(-0) && m.has('s') &&
                        m.get(a) === 'a' && m.get(b) === 'b' &&
                        m.size === 5
                """.trimIndent()
            )
        )
        // -0 键在存储中被归一化为 +0
        assertEquals(
            true,
            runtime.evalBoolean(
                """
                    var m = new Map(); m.set(-0, 1);
                    Object.is([].concat(Array.from(m.keys()))[0], 0) && m.has(-0) && m.has(0)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `Set 的值按 SameValueZero 比较`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean(
                """
                    var s = new Set();
                    s.add(NaN); s.add(NaN);
                    s.add(-0); s.add(0);
                    var o1 = {}, o2 = {};
                    s.add(o1); s.add(o2); s.add(o1);
                    s.size === 4 && s.has(NaN) && s.has(0) && s.has(o1) && s.has(o2)
                """.trimIndent()
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean(
                "var s = new Set(); s.add(-0); Object.is(Array.from(s)[0], 0)"
            )
        )
    }

    @Test
    fun `删除后重新插入的键出现在末尾`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "b,a",
            runtime.evalString(
                """
                    var m = new Map([['a', 1], ['b', 2]]);
                    m.delete('a'); m.set('a', 3);
                    Array.from(m.keys()).join(',')
                """.trimIndent()
            )
        )
        assertEquals(
            "2,1",
            runtime.evalString(
                "var s = new Set([1, 2]); s.delete(1); s.add(1); Array.from(s).join(',')"
            )
        )
    }

    @Test
    fun `大量增删后有效计数保持正确`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean(
                """
                    var m = new Map();
                    for (var i = 0; i < 1000; i++) m.set('k' + i, i);
                    for (var j = 0; j < 500; j++) m.delete('k' + j);
                    m.size === 500 && m.get('k999') === 999 && m.get('k0') === undefined
                """.trimIndent()
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean(
                """
                    var s = new Set();
                    for (var i = 0; i < 1000; i++) s.add(i);
                    for (var j = 0; j < 1000; j++) s.delete(j);
                    s.size === 0
                """.trimIndent()
            )
        )
    }

    @Test
    fun `没有活跃迭代器时墓碑会被压缩`() {
        val map = JSMap(prototype = null)
        repeat(1_000) { map.setEntry(JSNumber(it.toDouble()), JSNumber(it.toDouble())) }
        repeat(1_000) { map.deleteEntry(JSNumber(it.toDouble())) }

        assertEquals(0, map.liveSize)
        // 墓碑占多数且无迭代器，删除过程中应已压缩；全部删除后存储应清空
        assertEquals(0, map.storageSize(), "墓碑应在无活跃迭代器时被回收")

        val set = JSSet(prototype = null)
        repeat(1_000) { set.addValue(JSNumber(it.toDouble())) }
        repeat(1_000) { set.deleteValue(JSNumber(it.toDouble())) }
        assertEquals(0, set.liveSize)
        assertEquals(0, set.storageSize(), "Set 墓碑同样应被回收")
    }

    @Test
    fun `活跃迭代器期间不压缩以保持游标`() {
        val map = JSMap(prototype = null)
        repeat(100) { map.setEntry(JSNumber(it.toDouble()), JSNumber(it.toDouble())) }

        map.beginIteration()
        repeat(100) { map.deleteEntry(JSNumber(it.toDouble())) }
        // 迭代未结束时不能重排物理下标，否则游标会读到错误的条目
        assertEquals(100, map.storageSize(), "迭代期间不应压缩")
        assertEquals(0, map.liveSize)

        map.endIteration()
        assertEquals(0, map.storageSize(), "迭代结束后应回收墓碑")
    }

    @Test
    fun `压缩后仍保持插入顺序`() {
        val map = JSMap(prototype = null)
        repeat(200) { map.setEntry(JSString("k$it"), JSNumber(it.toDouble())) }
        // 删除前 150 个并触发压缩，再补入新键
        repeat(150) { map.deleteEntry(JSString("k$it")) }
        map.setEntry(JSString("new"), JSNumber(-1.0))

        assertEquals(51, map.liveSize)
        val keys = map.liveEntries().map { (it.first as JSString).value }
        assertEquals((150 until 200).map { "k$it" } + "new", keys)
    }

    @Test
    fun `WeakMap 与 WeakSet 复用同一索引语义`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean(
                """
                    var wm = new WeakMap();
                    var k1 = {}, k2 = {};
                    wm.set(k1, 1); wm.set(k2, 2);
                    var ok = wm.get(k1) === 1 && wm.has(k2) && wm.size === undefined;
                    wm.delete(k1);
                    ok && wm.has(k1) === false && wm.get(k2) === 2
                """.trimIndent()
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean(
                """
                    var ws = new WeakSet();
                    var a = {}, b = {};
                    ws.add(a); ws.add(b); ws.add(a);
                    var ok = ws.has(a) && ws.has(b);
                    ws.delete(a);
                    ok && ws.has(a) === false && ws.has(b)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `集合关系方法在索引存储上保持结果正确`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean(
                """
                    var a = new Set([1, 2, 3]), b = new Set([3, 4]);
                    Array.from(a.union(b)).join(',') === '1,2,3,4' &&
                        Array.from(a.intersection(b)).join(',') === '3' &&
                        Array.from(a.difference(b)).join(',') === '1,2' &&
                        Array.from(a.symmetricDifference(b)).join(',') === '1,2,4' &&
                        a.isSupersetOf(b) === false &&
                        new Set([3]).isSubsetOf(a) === true &&
                        a.isDisjointFrom(new Set([9])) === true
                """.trimIndent()
            )
        )
    }

    @Test
    fun `迭代期间的增删仍然实时可见`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "1,3",
            runtime.evalString(
                """
                    var s = new Set([1, 2]), seen = [];
                    s.forEach(function (v) {
                        seen.push(v);
                        if (v === 1) { s.delete(2); s.add(3); }
                    });
                    seen.join(',')
                """.trimIndent()
            )
        )
        assertTrue(
            runtime.evalBoolean(
                """
                    var m = new Map([[1, 1]]);
                    var it = m.keys(); it.next();
                    m.set(2, 2);
                    JSON.stringify(it.next()) === '{"value":2,"done":false}'
                """.trimIndent()
            )
        )
    }
}
