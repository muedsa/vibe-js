package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 针对审查阶段发现的 P0 问题进行回归验证。
 */
class ConformanceTest {

    @Test
    fun `Map与Set各类迭代器在清空后继续读取新增项`() = runTest {
        val runtime = TestRuntime()
        // 同一场景覆盖 Map 与 Set，结果同时由 Node 差分验证。
        assertEquals(
            "[[3,4],[30,40],[[3,30],[4,40]],[3,4],[3,4],[[3,3],[4,4]]]",
            runtime.evalString(
                """
                (function () {
                  var out = [];
                  [Map, Set].forEach(function (C) {
                    ['keys', 'values', 'entries'].forEach(function (method) {
                      var c = C === Map ? new Map([[1,10],[2,20]]) : new Set([1,2]);
                      var it = c[method]();
                      it.next();
                      c.clear();
                      if (C === Map) { c.set(3,30); c.set(4,40); }
                      else { c.add(3); c.add(4); }
                      out.push(Array.from(it));
                    });
                  });
                  return JSON.stringify(out);
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `集合反复清空及删除重加保持游标且已结束迭代器不会复活`() = runTest {
        val runtime = TestRuntime()
        // 同一场景覆盖 Map 与 Set，结果同时由 Node 差分验证。
        assertEquals(
            "[[[4],[4],true,true],[[4],[4],true,true]]",
            runtime.evalString(
                """
                (function () {
                  return JSON.stringify([Map, Set].map(function (C) {
                    var c = C === Map ? new Map([[1,1],[2,2]]) : new Set([1,2]);
                    function add(v) { if (C === Map) c.set(v,v); else c.add(v); }
                    var before = c.keys(), partial = c.keys();
                    partial.next();
                    c.clear(); add(3); c.clear(); add(3); c.delete(3); add(4);
                    var a = Array.from(before), b = Array.from(partial);
                    c.clear(); add(5);
                    return [a,b,before.next().done,partial.next().done];
                  }));
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `嵌套forEach清空集合后内外层分别继续访问新元素`() = runTest {
        val runtime = TestRuntime()
        // 同一场景覆盖 Map 与 Set，结果同时由 Node 差分验证。
        assertEquals(
            "[\"o1,i3,i5,i6,o5,o6\",\"o1,i3,i5,i6,o5,o6\"]",
            runtime.evalString(
                """
                (function () {
                  return JSON.stringify([Map, Set].map(function (C) {
                    var c = C === Map ? new Map([[1,1],[2,2]]) : new Set([1,2]), seen = [];
                    function add(v) { if (C === Map) c.set(v,v); else c.add(v); }
                    c.forEach(function (value, key) {
                      seen.push('o' + key);
                      if (key === 1) {
                        c.clear(); add(3); add(4);
                        c.forEach(function (inner, innerKey) {
                          seen.push('i' + innerKey);
                          if (innerKey === 3) { c.clear(); add(5); add(6); }
                        });
                      }
                    });
                    return seen.join(',');
                  }));
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `清空后没有新增项的遍历正常结束且后续新增不恢复已结束迭代器`() = runTest {
        val runtime = TestRuntime()
        // 同一场景覆盖 Map 与 Set，结果同时由 Node 差分验证。
        assertEquals(
            "[[true,true,1],[true,true,1]]",
            runtime.evalString(
                """
                (function () {
                  return JSON.stringify([Map, Set].map(function (C) {
                    var c = C === Map ? new Map([[1,1],[2,2]]) : new Set([1,2]);
                    var it = c.keys(); it.next(); c.clear();
                    var ended = it.next().done;
                    if (C === Map) c.set(3,3); else c.add(3);
                    var visits = 0;
                    c.forEach(function () { visits++; c.clear(); });
                    if (C === Map) c.set(4,4); else c.add(4);
                    return [ended,it.next().done,visits];
                  }));
                })()
                """.trimIndent(),
            ),
        )
    }


    @Test
    fun `全局 this 与 globalThis`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("this === globalThis"))
        assertEquals(true, runtime.evalBoolean("globalThis === this"))
    }

    @Test
    fun `全局变量与全局对象联动`() = runTest {
        val runtime = TestRuntime()
        runtime.eval("var declared = 5;")
        assertEquals(5.0, runtime.evalNumber("globalThis.declared"))
        runtime.eval("implicit = 7;")
        assertEquals(7.0, runtime.evalNumber("globalThis.implicit"))
        assertEquals(7.0, runtime.evalNumber("implicit"))
    }

    @Test
    fun `数组 length 赋值与稀疏赋值`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "0:undefined",
            runtime.evalString("var a = [1, 2, 3]; a.length = 0; a.length + ':' + a[0]")
        )
        assertEquals(
            "3:undefined:9",
            runtime.evalString("var a = []; a[2] = 9; a.length + ':' + a[0] + ':' + a[2]")
        )
        assertEquals(3.0, runtime.evalNumber("var a = [1, , 3]; a.length"))
    }

    @Test
    fun `字符串关系比较`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("'apple' < 'banana'"))
        assertEquals(false, runtime.evalBoolean("'banana' < 'apple'"))
    }

    @Test
    fun `字符串边界方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals("", runtime.evalString("'abc'.charAt(5)"))
        assertEquals("c", runtime.evalString("'abc'.at(-1)"))
        assertEquals(1.0, runtime.evalNumber("'aundefinedb'.split().length"))
        assertEquals("x", runtime.evalString("'x'.padStart(3, '')"))
        assertEquals("xabab", runtime.evalString("'x'.padEnd(5, 'ab')"))
        assertEquals("aaa", runtime.evalString("'a'.repeat(3)"))
    }

    @Test
    fun `parseFloat 与 Number 判断`() = runTest {
        val runtime = TestRuntime()
        assertEquals(12.0, runtime.evalNumber("parseFloat('12px')"))
        assertEquals(false, runtime.evalBoolean("Number.isNaN('abc')"))
        assertTrue(runtime.evalNumber("Number('12px')").isNaN())
    }

    @Test
    fun `Array 构造器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("new Array(3).length"))
        assertEquals(3.0, runtime.evalNumber("Array(3).length"))
        assertEquals(1.0, runtime.evalNumber("Array('3').length"))
        assertEquals(3.0, runtime.evalNumber("Array.from('abc').length"))
    }

    @Test
    fun `Error 子类构造器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "TypeError",
            runtime.evalString("var n = ''; try { throw new TypeError('x'); } catch (e) { n = e.name; } n")
        )
        assertEquals("RangeError: msg", runtime.evalString("'' + new RangeError('msg')"))
    }

    @Test
    fun `JSON 数字与特殊值`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1", runtime.evalString("JSON.stringify(1)"))
        assertEquals("0.5", runtime.evalString("JSON.stringify(0.5)"))
        assertEquals("null", runtime.evalString("JSON.stringify(NaN)"))
        assertEquals("null", runtime.evalString("JSON.stringify(Infinity)"))
        assertEquals("undefined", runtime.evalString("'' + JSON.stringify(undefined)"))
        assertEquals("undefined", runtime.evalString("'' + JSON.stringify(function () {})"))
        assertFailsWith<JSException> {
            runtime.eval("var o = {}; o.self = o; JSON.stringify(o);")
        }
    }

    @Test
    fun `JSON 解析`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("JSON.parse('{\"a\":1}').a"))
        assertEquals("x", runtime.evalString("JSON.parse('{\"a\":\"x\"}').a"))
        assertEquals(2.0, runtime.evalNumber("JSON.parse('[1,2]').length"))
    }

    @Test
    fun `Math 边界`() = runTest {
        val runtime = TestRuntime()
        assertEquals(-2.0, runtime.evalNumber("Math.cbrt(-8)"))
        assertEquals(3.0, runtime.evalNumber("Math.cbrt(27)"))
        assertTrue(runtime.evalNumber("Math.max(1, NaN)").isNaN())
        assertEquals(4.0, runtime.evalNumber("Math.ceil(3.2)"))
    }

    @Test
    fun `数字格式化`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1", runtime.evalString("'' + 1"))
        assertEquals("0.30000000000000004", runtime.evalString("'' + (0.1 + 0.2)"))
        assertEquals("1e+21", runtime.evalString("'' + 1e21"))
        assertEquals("0.000001", runtime.evalString("'' + 0.000001"))
    }

    @Test
    fun `join 对 null 和 undefined 输出空串`() = runTest {
        val runtime = TestRuntime()
        assertEquals("||1", runtime.evalString("[null, undefined, 1].join('|')"))
        assertEquals(",", runtime.evalString("'' + [null, undefined]"))
    }
}
