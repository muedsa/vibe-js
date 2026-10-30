package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Symbol / Map / Set / WeakMap / WeakSet / 迭代器协议补全测试。
 */
class BuiltinCollectionSymbolTest {

    @Test
    fun `Symbol 的 description 与 typeof`() = runTest {
        val runtime = TestRuntime()
        assertEquals("x", runtime.evalString("Symbol('x').description"))
        assertEquals("undefined", runtime.evalString("'' + Symbol().description"))
        assertEquals("symbol", runtime.evalString("typeof Symbol()"))
        assertEquals("symbol", runtime.evalString("typeof Symbol.iterator"))
        assertEquals(true, runtime.evalBoolean("Symbol.iterator === Symbol.iterator"))
        assertEquals(false, runtime.evalBoolean("Symbol.iterator === Symbol.asyncIterator"))
    }

    @Test
    fun `Symbol_toStringTag 自定义类型标签`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "[object Custom]",
            runtime.evalString(
                "var o = {}; o[Symbol.toStringTag] = 'Custom'; Object.prototype.toString.call(o)"
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean("var o = {}; o[Symbol.toStringTag] = 'X'; Object.prototype.toString.call(o) === '[object X]'")
        )
    }

    @Test
    fun `Symbol 键在 JSON 与 Object_keys 中被忽略`() = runTest {
        val runtime = TestRuntime()
        assertEquals("{}", runtime.evalString("JSON.stringify({ [Symbol('k')]: 1 })"))
        assertEquals("a", runtime.evalString("var o = { a: 1 }; o[Symbol('k')] = 2; Object.keys(o).join(',')"))
        assertEquals(
            1.0,
            runtime.evalNumber("var o = { a: 1 }; o[Symbol('k')] = 2; Object.getOwnPropertySymbols(o).length")
        )
    }

    @Test
    fun `自定义迭代器支持 for-of 展开与 Array_from`() = runTest {
        val runtime = TestRuntime()
        val makeIterable = """
            function makeIterable() {
                var obj = {};
                obj[Symbol.iterator] = function () {
                    var index = 0;
                    return {
                        next: function () {
                            if (index < 3) {
                                return { value: index++, done: false };
                            }
                            return { done: true };
                        }
                    };
                };
                return obj;
            }
        """.trimIndent()
        assertEquals(
            "012",
            runtime.evalString(
                """
                $makeIterable
                var s = '';
                for (var v of makeIterable()) { s += v; }
                s;
                """.trimIndent()
            )
        )
        assertEquals(
            "0,1,2",
            runtime.evalString("$makeIterable [...makeIterable()].join(',')")
        )
        assertEquals(
            3.0,
            runtime.evalNumber("$makeIterable Array.from(makeIterable()).length")
        )
    }

    @Test
    fun `Map_forEach 与 clear`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "a:1:true",
            runtime.evalString(
                """
                var m = new Map([['a', 1]]);
                var s = '';
                m.forEach(function (value, key, map) {
                    s = key + ':' + value + ':' + (map === m);
                });
                s;
                """.trimIndent()
            )
        )
        assertEquals(
            "0",
            runtime.evalString(
                "var m = new Map([['a', 1], ['b', 2]]); m.forEach(function () {}); m.clear(); '' + m.size"
            )
        )
        assertEquals("true", runtime.evalString("var m = new Map([['a', 1]]); m.clear(); m.keys().next().done + ''"))
    }

    @Test
    fun `Map 保留插入顺序与 SameValueZero 键`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "b,a",
            runtime.evalString(
                """
                var m = new Map();
                m.set('a', 1);
                m.set('b', 2);
                m.delete('a');
                m.set('a', 3);
                [...m.keys()].join(',');
                """.trimIndent()
            )
        )
        assertEquals(
            "z:true:true:2",
            runtime.evalString(
                """
                var m = new Map();
                m.set(-0, 'z');
                var value = m.get(0);
                var hasZero = m.has(0);
                m.set(NaN, 'nan');
                value + ':' + hasZero + ':' + m.has(NaN) + ':' + m.size;
                """.trimIndent()
            )
        )
        assertEquals(
            "1:undefined",
            runtime.evalString(
                """
                var m = new Map();
                var key1 = {};
                var key2 = {};
                m.set(key1, 1);
                m.get(key1) + ':' + m.get(key2);
                """.trimIndent()
            )
        )
    }

    @Test
    fun `Set_forEach 与 delete clear`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "1:1:true",
            runtime.evalString(
                """
                var s = new Set([1]);
                var result = '';
                s.forEach(function (value, key, set) {
                    result = value + ':' + key + ':' + (set === s);
                });
                result;
                """.trimIndent()
            )
        )
        assertEquals(
            "true:false:1",
            runtime.evalString(
                """
                var s = new Set([1, 2]);
                var removed = s.delete(1);
                var missing = s.delete(9);
                removed + ':' + missing + ':' + s.size;
                """.trimIndent()
            )
        )
        assertEquals(
            0.0,
            runtime.evalNumber("var s = new Set([1, 2]); s.clear(); s.size")
        )
    }

    @Test
    fun `WeakMap 与 WeakSet 拒绝非对象键`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "TypeError:TypeError",
            runtime.evalString(
                """
                var m = new WeakMap();
                var n = new WeakSet();
                var first = '';
                var second = '';
                try { m.set(1, 2); } catch (e) { first = e.name; }
                try { n.add('x'); } catch (e) { second = e.name; }
                first + ':' + second;
                """.trimIndent()
            )
        )
        assertEquals(
            "undefined",
            runtime.evalString("var m = new WeakMap(); '' + m.get(1)")
        )
        assertEquals(
            "TypeError:TypeError:TypeError",
            runtime.evalString(
                """
                var first = '';
                var second = '';
                var third = '';
                try { new WeakMap([[1, 2]]); } catch (e) { first = e.name; }
                try { new WeakSet([1]); } catch (e) { second = e.name; }
                try { new WeakMap([[]]); } catch (e) { third = e.name; }
                first + ':' + second + ':' + third;
                """.trimIndent()
            )
        )
        assertEquals(
            1.0,
            runtime.evalNumber("var k = {}; new WeakMap([[k, 1]]).get(k)")
        )
    }

    @Test
    fun `Map Set WeakMap WeakSet 的类型标签`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "[object Map]:[object Set]:[object WeakMap]:[object WeakSet]",
            runtime.evalString(
                """
                Object.prototype.toString.call(new Map()) + ':' +
                    Object.prototype.toString.call(new Set()) + ':' +
                    Object.prototype.toString.call(new WeakMap()) + ':' +
                    Object.prototype.toString.call(new WeakSet());
                """.trimIndent()
            )
        )
    }

    @Test
    fun `Object toString 的常见类型标签`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "[object Date]:[object RegExp]:[object Error]:[object Number]:[object String]",
            runtime.evalString(
                """
                Object.prototype.toString.call(new Date(0)) + ':' +
                    Object.prototype.toString.call(/a/) + ':' +
                    Object.prototype.toString.call(new Error('x')) + ':' +
                    Object.prototype.toString.call(new Number(1)) + ':' +
                    Object.prototype.toString.call('x');
                """.trimIndent()
            )
        )
        assertEquals(
            "[object Null]:[object Undefined]",
            runtime.evalString(
                "Object.prototype.toString.call(null) + ':' + Object.prototype.toString.call(undefined)"
            )
        )
    }

    @Test
    fun `Function_toString 与构造器全局作用域`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean("function foo(a) {}; foo.toString().indexOf('function foo') === 0")
        )
        assertEquals(
            "undefined:function",
            runtime.evalString(
                """
                function outer() {
                    var local = 1;
                    var f = new Function('return typeof local');
                    return f();
                }
                outer() + ':' + (new Function('return typeof outer'))();
                """.trimIndent()
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean("function f() { return Array.isArray(arguments); } f() === false")
        )
    }

    @Test
    fun `Error 层级与自定义错误子类`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "true:true:TypeError:x",
            runtime.evalString(
                """
                var e = new TypeError('x');
                (e instanceof TypeError) + ':' + (e instanceof Error) + ':' + e.name + ':' + e.message;
                """.trimIndent()
            )
        )
        assertEquals("Error: msg", runtime.evalString("'' + new Error('msg')"))
        assertEquals(
            "true:true:boom",
            runtime.evalString(
                """
                class AppError extends Error {
                    constructor(message) {
                        super(message);
                    }
                }
                var caught = null;
                try { throw new AppError('boom'); } catch (e) { caught = e; }
                (caught instanceof AppError) + ':' + (caught instanceof Error) + ':' + caught.message;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `throw 非 Error 值原样传播`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "string:hello",
            runtime.evalString(
                "var r = null; try { throw 'hello'; } catch (e) { r = typeof e + ':' + e; } r"
            )
        )
    }

    @Test
    fun `Map 与 Set 对自定义迭代器构造`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "1,2",
            runtime.evalString(
                """
                var iterable = {};
                iterable[Symbol.iterator] = function () {
                    var index = 0;
                    var values = [1, 2];
                    return {
                        next: function () {
                            return index < values.length
                                ? { value: values[index++], done: false }
                                : { done: true };
                        }
                    };
                };
                [...new Set(iterable)].join(',');
                """.trimIndent()
            )
        )
    }
}
