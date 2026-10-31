package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Object / Date / RegExp 的补全测试。
 */
class BuiltinObjectDateRegExpTest {

    // ================================================================
    // Object
    // ================================================================

    @Test
    fun `Object_keys 与 getOwnPropertyNames 枚举数组索引`() = runTest {
        val runtime = TestRuntime()
        assertEquals("0,1", runtime.evalString("Object.keys([1, 2]).join(',')"))
        assertEquals("0,1,length", runtime.evalString("Object.getOwnPropertyNames([1, 2]).join(',')"))
        assertEquals(0.0, runtime.evalNumber("Object.keys(new Array(3)).length"))
        assertEquals("0,x", runtime.evalString("var a = [1]; a.x = 2; Object.keys(a).join(',')"))
        assertEquals(
            "01",
            runtime.evalString("var s = ''; for (var k in [1, 2]) { s += k; } s")
        )
    }

    @Test
    fun `Object_getOwnPropertyDescriptor 数据属性`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "1:true:true:true",
            runtime.evalString(
                """
                var o = {};
                Object.defineProperty(o, 'x', { value: 1, writable: true, enumerable: true, configurable: true });
                var d = Object.getOwnPropertyDescriptor(o, 'x');
                d.value + ':' + d.writable + ':' + d.enumerable + ':' + d.configurable;
                """.trimIndent()
            )
        )
        assertEquals(
            "1:false:false:false",
            runtime.evalString(
                """
                var o = {};
                Object.defineProperty(o, 'x', { value: 1 });
                var d = Object.getOwnPropertyDescriptor(o, 'x');
                d.value + ':' + d.writable + ':' + d.enumerable + ':' + d.configurable;
                """.trimIndent()
            )
        )
        assertEquals(
            "undefined",
            runtime.evalString("'' + Object.getOwnPropertyDescriptor({}, 'missing')")
        )
    }

    @Test
    fun `Object_getOwnPropertyDescriptor 访问器属性`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "true:false:true",
            runtime.evalString(
                """
                var o = {};
                Object.defineProperty(o, 'x', {
                    get: function () { return 1; },
                    set: function (v) {},
                    enumerable: true
                });
                var d = Object.getOwnPropertyDescriptor(o, 'x');
                (typeof d.get === 'function') + ':' + ('value' in d) + ':' + d.enumerable;
                """.trimIndent()
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean(
                "var o = {}; Object.defineProperty(o, 'x', { get: function () { return 1; } }); " +
                    "typeof Object.getOwnPropertyDescriptor(o, 'x').set === 'undefined'"
            )
        )
    }

    @Test
    fun `defineProperty 省略字段时保留已有属性特征`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "2:true:true:true",
            runtime.evalString(
                """
                var o = {};
                Object.defineProperty(o, 'x', { value: 1, writable: true, enumerable: true, configurable: true });
                Object.defineProperty(o, 'x', { value: 2 });
                var d = Object.getOwnPropertyDescriptor(o, 'x');
                d.value + ':' + d.writable + ':' + d.enumerable + ':' + d.configurable;
                """.trimIndent()
            )
        )
        assertEquals(
            "1:true:true:true",
            runtime.evalString(
                """
                var o = {};
                Object.defineProperty(o, 'x', { get: function () { return 1; }, enumerable: true, configurable: true });
                Object.defineProperty(o, 'x', { enumerable: true });
                var d = Object.getOwnPropertyDescriptor(o, 'x');
                o.x + ':' + (typeof d.get === 'function') + ':' + d.enumerable + ':' + d.configurable;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `不可配置属性重新定义违反不变量时抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            "var o = {}; Object.defineProperty(o, 'x', { value: 1 }); Object.defineProperty(o, 'x', { value: 2 });",
            "var o = {}; Object.defineProperty(o, 'x', { value: 1 }); Object.defineProperty(o, 'x', { enumerable: true });",
            "var o = {}; Object.defineProperty(o, 'x', { get: function () { return 1; } }); Object.defineProperty(o, 'x', { get: function () { return 2; } });",
        )) {
            val error = assertFailsWith<JSException> { runtime.eval(code) }
            assertEquals("TypeError", (error.value as JSError).errorName)
        }
    }

    @Test
    fun `defineProperties 忽略非枚举描述符条目`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            false,
            runtime.evalBoolean(
                """
                var props = {};
                Object.defineProperty(props, 'x', {
                    value: { value: 7 },
                    enumerable: false
                });
                Object.defineProperties({}, props).hasOwnProperty('x');
                """.trimIndent()
            )
        )
    }

    @Test
    fun `描述符 getter 与 setter 必须是函数`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            "Object.defineProperty({}, 'x', { get: 1 })",
            "Object.defineProperty({}, 'x', { set: 's' })",
            "Object.defineProperties({}, { x: { get: {} } })",
        )) {
            val error = assertFailsWith<JSException> { runtime.eval(code) }
            assertEquals("TypeError", (error.value as JSError).errorName)
        }
        assertEquals(
            "undefined",
            runtime.evalString(
                "var o = {}; Object.defineProperty(o, 'x', { get: undefined, set: undefined, configurable: true }); typeof o.x"
            )
        )
    }

    @Test
    fun `String 原型自身作为字符串接收者`() = runTest {
        val runtime = TestRuntime()
        assertEquals("", runtime.evalString("String.prototype.valueOf()"))
        assertEquals("", runtime.evalString("String.prototype.toString()"))
    }

    @Test
    fun `混合数据与访问器描述符抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> {
            runtime.eval(
                "Object.defineProperty({}, 'x', { get: function () { return 1; }, value: 2 })"
            )
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `defineProperties 接受原始值作为属性表`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("var o = { a: 1 }; Object.defineProperties(o, false) === o ? o.a : 0"))
        val error = assertFailsWith<JSException> {
            runtime.eval("Object.defineProperties({}, null)")
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `Object_create 从继承描述符字段读取属性特征`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "9:false",
            runtime.evalString(
                """
                var proto = { value: 9 };
                Object.defineProperty(proto, 'writable', { set: function () {} });
                var F = function () {};
                F.prototype = proto;
                var desc = new F();
                var o = Object.create({}, { x: desc });
                var d = Object.getOwnPropertyDescriptor(o, 'x');
                d.value + ':' + d.writable;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `Object_setPrototypeOf 与 getPrototypeOf`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "true:1:true",
            runtime.evalString(
                """
                var proto = { x: 1 };
                var o = {};
                var returned = Object.setPrototypeOf(o, proto);
                (returned === o) + ':' + o.x + ':' + (Object.getPrototypeOf(o) === proto);
                """.trimIndent()
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean("var o = {}; Object.setPrototypeOf(o, null); Object.getPrototypeOf(o) === null")
        )
        assertFailsWith<JSException> {
            runtime.eval("Object.setPrototypeOf({}, 1)")
        }
    }

    @Test
    fun `Object_create 空原型对象`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "true:true:false",
            runtime.evalString(
                """
                var o = Object.create(null);
                var noToString = (typeof o.toString === 'undefined');
                (Object.getPrototypeOf(o) === null) + ':' + noToString + ':' + ('hasOwnProperty' in o);
                """.trimIndent()
            )
        )
        assertEquals(1.0, runtime.evalNumber("var o = Object.create(null); o.a = 1; o.a"))
    }

    @Test
    fun `Object_preventExtensions 与 isExtensible`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("Object.isExtensible({})"))
        assertEquals(
            "false:undefined",
            runtime.evalString(
                "var o = { a: 1 }; Object.preventExtensions(o); o.b = 2; Object.isExtensible(o) + ':' + o.b"
            )
        )
        assertEquals(false, runtime.evalBoolean("Object.isExtensible(Object.preventExtensions({}))"))
    }

    @Test
    fun `Object_seal 与 isSealed`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "true:true:false:2",
            runtime.evalString(
                """
                var o = { a: 1 };
                var returned = Object.seal(o);
                o.a = 2;
                (returned === o) + ':' + Object.isSealed(o) + ':' + (delete o.a) + ':' + o.a;
                """.trimIndent()
            )
        )
        assertEquals(
            "true:true",
            runtime.evalString("var o = {}; Object.freeze(o); Object.isFrozen(o) + ':' + Object.isSealed(o)")
        )
        assertEquals(false, runtime.evalBoolean("var o = { a: 1 }; Object.seal(o); Object.isFrozen(o)"))
    }

    @Test
    fun `Object_propertyIsEnumerable 与 isPrototypeOf`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "true:false:false",
            runtime.evalString(
                """
                var o = { a: 1 };
                Object.defineProperty(o, 'b', { value: 2, enumerable: false });
                o.propertyIsEnumerable('a') + ':' + o.propertyIsEnumerable('b') + ':' + o.propertyIsEnumerable('toString');
                """.trimIndent()
            )
        )
        assertEquals(
            "true:true:false",
            runtime.evalString(
                """
                var proto = {};
                var o = Object.create(proto);
                proto.isPrototypeOf(o) + ':' + Object.prototype.isPrototypeOf(o) + ':' + o.isPrototypeOf(proto);
                """.trimIndent()
            )
        )
    }

    @Test
    fun `Object_assign 复制 getter 与 Symbol 键`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            5.0,
            runtime.evalNumber(
                "var src = {}; Object.defineProperty(src, 'x', { get: function () { return 5; }, enumerable: true }); Object.assign({}, src).x"
            )
        )
        assertEquals(
            1.0,
            runtime.evalNumber(
                "var s = Symbol('s'); var src = {}; src[s] = 1; Object.assign({}, src)[s]"
            )
        )
        assertEquals(
            0.0,
            runtime.evalNumber(
                "var src = {}; Object.defineProperty(src, 'hidden', { value: 1, enumerable: false }); Object.keys(Object.assign({}, src)).length"
            )
        )
    }

    // ================================================================
    // Date
    // ================================================================

    @Test
    fun `Date 的 UTC 分量读取`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "2020:0:2:3:4:5:6:4",
            runtime.evalString(
                """
                var d = new Date(Date.UTC(2020, 0, 2, 3, 4, 5, 6));
                d.getUTCFullYear() + ':' + d.getUTCMonth() + ':' + d.getUTCDate() + ':' +
                    d.getUTCHours() + ':' + d.getUTCMinutes() + ':' + d.getUTCSeconds() + ':' +
                    d.getUTCMilliseconds() + ':' + d.getUTCDay();
                """.trimIndent()
            )
        )
        assertEquals(
            "2020:0:2:3:4:5:6:4",
            runtime.evalString(
                """
                var d = new Date(Date.UTC(2020, 0, 2, 3, 4, 5, 6));
                d.getFullYear() + ':' + d.getMonth() + ':' + d.getDate() + ':' +
                    d.getHours() + ':' + d.getMinutes() + ':' + d.getSeconds() + ':' +
                    d.getMilliseconds() + ':' + d.getDay();
                """.trimIndent()
            )
        )
        assertEquals(0.0, runtime.evalNumber("new Date(0).getTimezoneOffset()"))
    }

    @Test
    fun `Date 的 setter 修改与返回时间值`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "2001:0:1",
            runtime.evalString(
                """
                var d = new Date(0);
                d.setUTCFullYear(2001);
                d.getUTCFullYear() + ':' + d.getUTCMonth() + ':' + d.getUTCDate();
                """.trimIndent()
            )
        )
        assertEquals(
            86400000.0,
            runtime.evalNumber("var d = new Date(0); d.setUTCDate(2); d.getTime()")
        )
        assertEquals(
            3600000.0,
            runtime.evalNumber("var d = new Date(0); d.setUTCHours(1); d.getTime()")
        )
        assertEquals(
            0.0,
            runtime.evalNumber("var d = new Date(0); d.setTime(0); d.getTime()")
        )
    }

    @Test
    fun `Date 的月份与日期溢出归一化`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "2021-01-01T00:00:00.000Z",
            runtime.evalString("new Date(Date.UTC(2020, 12, 1)).toISOString()")
        )
        assertEquals(
            "2019-12-01T00:00:00.000Z",
            runtime.evalString("new Date(Date.UTC(2020, -1, 1)).toISOString()")
        )
        assertEquals(
            "2020-03-02T00:00:00.000Z",
            runtime.evalString("var d = new Date(Date.UTC(2020, 0, 31)); d.setUTCMonth(1); d.toISOString()")
        )
        assertEquals(
            "2020-01-02T01:00:00.000Z",
            runtime.evalString("new Date(Date.UTC(2020, 0, 1, 25)).toISOString()")
        )
    }

    @Test
    fun `Date 的字符串化方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", runtime.evalString("new Date(0).toUTCString()"))
        assertEquals("Thu Jan 01 1970", runtime.evalString("new Date(0).toDateString()"))
        assertEquals("00:00:00 GMT", runtime.evalString("new Date(0).toTimeString()"))
        assertEquals("string", runtime.evalString("typeof Date()"))
        assertEquals(true, runtime.evalBoolean("Date().indexOf('GMT') > 0"))
        assertEquals("string", runtime.evalString("typeof new Date(0).toString()"))
    }

    @Test
    fun `Date 的 JSON 序列化`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "\"1970-01-01T00:00:00.000Z\"",
            runtime.evalString("JSON.stringify(new Date(0))")
        )
        assertEquals(
            "null",
            runtime.evalString("'' + new Date('not-a-date').toJSON()")
        )
        assertEquals(
            "null",
            runtime.evalString("JSON.stringify(new Date('not-a-date'))")
        )
    }

    @Test
    fun `Date 无效值与错误`() = runTest {
        val runtime = TestRuntime()
        assertTrue(runtime.evalNumber("new Date('not-a-date').getTime()").isNaN())
        assertTrue(runtime.evalNumber("Date.parse('not-a-date')").isNaN())
        val error = assertFailsWith<JSException> {
            runtime.eval("new Date('not-a-date').toISOString()")
        }
        assertEquals("RangeError", (error.value as JSError).errorName)
        assertEquals("Invalid Date", runtime.evalString("new Date('not-a-date').toString()"))
    }

    // ================================================================
    // RegExp
    // ================================================================

    @Test
    fun `RegExp 的 source flags 与标志属性`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "ab:gi:true:true:false",
            runtime.evalString(
                """
                var r = /ab/gi;
                r.source + ':' + r.flags + ':' + r.global + ':' + r.ignoreCase + ':' + r.multiline;
                """.trimIndent()
            )
        )
        assertEquals("(?:)", runtime.evalString("new RegExp('').source"))
        assertEquals(true, runtime.evalBoolean("/a/s.dotAll"))
        assertEquals(true, runtime.evalBoolean("/a/u.unicode"))
        assertEquals(true, runtime.evalBoolean("/a/y.sticky"))
        assertEquals(true, runtime.evalBoolean("/a/m.multiline"))
    }

    @Test
    fun `RegExp 构造器复用与重新编译`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("var r = /a/g; RegExp(r) === r"))
        assertEquals(true, runtime.evalBoolean("var r = /a/g; RegExp(r, 'i') !== r && RegExp(r, 'i').ignoreCase"))
        assertEquals(true, runtime.evalBoolean("new RegExp('a', 'i').test('A')"))
        assertEquals("a", runtime.evalString("new RegExp('a').source"))
    }

    @Test
    fun `RegExp 全局匹配的 lastIndex 语义`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "1:2:false:0",
            runtime.evalString(
                """
                var r = /a/g;
                var first = r.test('aa');
                var afterFirst = r.lastIndex;
                var second = r.test('aa');
                var afterSecond = r.lastIndex;
                var third = r.test('aa');
                afterFirst + ':' + afterSecond + ':' + third + ':' + r.lastIndex;
                """.trimIndent()
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean("var r = /a/g; r.lastIndex = 1; r.test('ba')")
        )
        assertEquals(
            0.0,
            runtime.evalNumber("var r = /a/; r.test('a'); r.lastIndex")
        )
    }

    @Test
    fun `RegExp exec 结果包含捕获组与位置`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "3:ab:a:b:0:ab",
            runtime.evalString(
                """
                var m = /(a)(b)/.exec('ab');
                m.length + ':' + m[0] + ':' + m[1] + ':' + m[2] + ':' + m.index + ':' + m.input;
                """.trimIndent()
            )
        )
        assertEquals("null", runtime.evalString("'' + /x/.exec('abc')"))
    }

    @Test
    fun `未参与匹配的捕获组为 undefined`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean("var m = /(a)?b/.exec('b'); m[1] === undefined && (1 in m)")
        )
        assertEquals(
            "undefined",
            runtime.evalString(
                "'b'.replace(/(a)?b/, function (match, group) { return typeof group; })"
            )
        )
    }

    @Test
    fun `回退引擎用于 Kotlin Regex 不支持的反向引用 lookahead`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean("""var r = /(["'])(?:(?!\1)[^\\]|\\.)*?\1/; r.test('"a"')""")
        )
        assertEquals(
            true,
            runtime.evalBoolean("""var r = /(["'])(?:(?!\1)[^\\]|\\.)*?\1/; r.test("'b'")""")
        )
        assertEquals(
            """"a"""",
            runtime.evalString("""var r = /(["'])(?:(?!\1)[^\\]|\\.)*?\1/; r.exec('x "a" y')[0]""")
        )
        assertEquals(
            "[\"a\"]",
            runtime.evalString("""'"a"'.replace(/(["'])(?:(?!\1)[^\\]|\\.)*?\1/, '[$&]')""")
        )
    }

    @Test
    fun `RegExp 非法模式与非法标志抛 SyntaxError`() = runTest {
        val runtime = TestRuntime()
        val patternError = assertFailsWith<JSException> { runtime.eval("new RegExp('[')") }
        assertEquals("SyntaxError", (patternError.value as JSError).errorName)
        val flagError = assertFailsWith<JSException> { runtime.eval("new RegExp('a', 'gg')") }
        assertEquals("SyntaxError", (flagError.value as JSError).errorName)
        val unknownFlagError = assertFailsWith<JSException> { runtime.eval("new RegExp('a', 'z')") }
        assertEquals("SyntaxError", (unknownFlagError.value as JSError).errorName)
    }
}
