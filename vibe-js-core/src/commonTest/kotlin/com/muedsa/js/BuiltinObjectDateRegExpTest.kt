package com.muedsa.js

import com.muedsa.js.runtime.DefaultHostEnvironment
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.HostEnvironment
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
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
        assertEquals(
            "00:00:00 GMT+0000 (Coordinated Universal Time)",
            runtime.evalString("new Date(0).toTimeString()"),
        )
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

    @Test
    fun `Date 本地方法使用宿主注入的时区偏移`() = runTest {
        val offset = 8L * 60 * 60 * 1000
        val engine = Engine(DefaultHostEnvironment(timeZoneOffset = { offset }))
        suspend fun number(code: String): Double = (engine.run(code) as JSNumber).value
        assertEquals(8.0, number("new Date(0).getHours()"))
        assertEquals(0.0, number("new Date(0).getUTCHours()"))
        assertEquals(-480.0, number("new Date(0).getTimezoneOffset()"))
        assertEquals(number("Date.UTC(2020, 0, 1)"), number("new Date(2020, 0, 1, 8, 0, 0).getTime()"))
        assertEquals(-offset.toDouble(), number("var d = new Date(0); d.setHours(0); d.getTime()"))
        assertEquals(number("Date.UTC(2020, 0, 1)"), number("Date.parse('2020-01-01T08:00:00')"))
        assertEquals(number("Date.UTC(2020, 0, 1, 8)"), number("Date.parse('2020-01-01T08:00:00Z')"))
        assertTrue((engine.run("new Date(0).toString()") as JSString).value.contains("GMT+0800"))
        assertEquals(70.0, number("new Date(0).getYear()"))
        assertEquals("Thu Jan 01 1970", (engine.run("new Date(0).toDateString()") as JSString).value)
        assertTrue(
            (engine.run("new Date(0).toTimeString()") as JSString).value
                .startsWith("08:00:00 GMT+0800")
        )
        assertEquals(
            number("Date.UTC(2020, 0, 1)"),
            number("new Date('2020-01-01T08:00:00').getTime()")
        )
        assertEquals(number("Date.UTC(2020, 0, 1)"), number("Date.parse('2020-01-01 08:00:00')"))
        assertEquals(
            number("Date.UTC(2020, 1, 1)"),
            number("var d = new Date(Date.UTC(2020, 0, 1)); d.setMonth(1); d.getTime()")
        )
        // 无效日期以 t = +0 的 UTC 字段为基准，再换算成 +08 本地时间
        assertEquals(-8.0 * 3_600_000.0, number("var d = new Date(NaN); d.setFullYear(1970); d.getTime()"))
        assertEquals(-8.0 * 3_600_000.0, number("var d = new Date(NaN); d.setYear(70); d.getTime()"))
        assertEquals(
            true,
            (engine.run(
                "var d = new Date(NaN); d.setFullYear(2020); " +
                    "d.getFullYear() === 2020 && d.getMonth() === 0 && " +
                    "d.getDate() === 1 && d.getHours() === 0"
            ) as JSBoolean).value,
        )
    }

    @Test
    fun `Date 时区偏移可随时刻变化并保持本地字符串往返`() = runTest {
        val dstStart = 1_590_940_800_000L
        val engine = Engine(
            DefaultHostEnvironment(
                timeZoneOffset = { if (it >= dstStart) 9L * 60 * 60 * 1000 else 8L * 60 * 60 * 1000 }
            )
        )
        suspend fun number(code: String): Double = (engine.run(code) as JSNumber).value
        // 2020-06-01 本地 09:00（+09）对应 UTC 00:00
        assertEquals(
            number("Date.UTC(2020, 5, 1, 0, 0, 0)"),
            number("new Date(2020, 5, 1, 9, 0, 0).getTime()")
        )
        assertEquals(23.0, number("new Date(${dstStart} - 1).getHours()"))
        assertEquals(1.0, number("new Date(${dstStart}).getHours()"))
        assertEquals(0.0, number("Date.parse(new Date(0).toString())"))
        assertEquals(0.0, number("Date.parse(new Date(0).toUTCString())"))
        // DST 切换后：无时区 ISO 解析与本地构造器一致，使用切换后的偏移
        assertEquals(
            number("Date.UTC(2020, 5, 1, 1, 30) - 9 * 3600000"),
            number("new Date(2020, 5, 1, 1, 30, 0).getTime()")
        )
        assertEquals(
            number("Date.UTC(2020, 5, 1, 1, 30) - 9 * 3600000"),
            number("Date.parse('2020-06-01T01:30:00')")
        )
    }

    @Test
    fun `Date 本地时区支持负偏移与半小时偏移`() = runTest {
        val negative = Engine(DefaultHostEnvironment(timeZoneOffset = { -3L * 3_600_000L - 30L * 60_000L }))
        suspend fun negNumber(code: String): Double = (negative.run(code) as JSNumber).value
        suspend fun negText(code: String): String = (negative.run(code) as JSString).value
        assertEquals(210.0, negNumber("new Date(0).getTimezoneOffset()"))
        assertEquals(20.0, negNumber("new Date(0).getHours()"))
        assertEquals(30.0, negNumber("new Date(0).getMinutes()"))
        assertEquals(69.0, negNumber("new Date(0).getYear()"))
        assertEquals(
            "Wed Dec 31 1969 20:30:00 GMT-0330 (UTC-03:30)",
            negText("new Date(0).toString()"),
        )
        assertEquals(0.0, negNumber("Date.parse(new Date(0).toString())"))
        // 纯日期 ISO 仍按 UTC，带时间无时区按宿主本地时间解释
        assertEquals(0.0, negNumber("new Date('2020-01-01').getTime() - Date.UTC(2020, 0, 1)"))
        assertEquals(
            0.0,
            negNumber(
                "new Date('2020-01-01T00:00:00').getTime() - (Date.UTC(2020, 0, 1) + 3 * 3600000 + 30 * 60000)"
            ),
        )

        val positive = Engine(DefaultHostEnvironment(timeZoneOffset = { 5L * 3_600_000L + 30L * 60_000L }))
        suspend fun posNumber(code: String): Double = (positive.run(code) as JSNumber).value
        suspend fun posText(code: String): String = (positive.run(code) as JSString).value
        assertEquals(-330.0, posNumber("new Date(0).getTimezoneOffset()"))
        assertEquals(
            "Thu Jan 01 1970 05:30:00 GMT+0530 (UTC+05:30)",
            posText("new Date(0).toString()"),
        )
        assertEquals(0.0, posNumber("Date.parse(new Date(0).toString())"))
        assertEquals(
            0.0,
            posNumber("new Date(2020, 0, 1).getTime() - (Date.UTC(2020, 0, 1) - 5.5 * 3600000)"),
        )
    }

    @Test
    fun `Date 构造器在本地偏移下对极值边界先换算后裁剪`() = runTest {
        val positive = Engine(DefaultHostEnvironment(timeZoneOffset = { 1L }))
        val posTime = (positive.run("new Date(275760, 8, 13, 0, 0, 0, 1).getTime()") as JSNumber).value
        assertEquals(8.64e15, posTime)
        val negative = Engine(DefaultHostEnvironment(timeZoneOffset = { -1L }))
        val negTime = (negative.run("new Date(-271821, 3, 20, 0, 0, 0, -1).getTime()") as JSNumber).value
        assertEquals(-8.64e15, negTime)
    }

    @Test
    fun `Date 的 setYear 在 NaN 参数下写入无效时间`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean("var d = new Date(0); isNaN(d.setYear(NaN)) && isNaN(d.getTime())"),
        )
        assertEquals(
            true,
            runtime.evalBoolean("var d = new Date(0); isNaN(d.setYear()) && isNaN(d.getTime())"),
        )
        assertEquals(
            true,
            runtime.evalBoolean("var d = new Date(NaN); isNaN(d.setYear(NaN)) && isNaN(d.getTime())"),
        )
        // 小数先截断，0..99 仍映射到 1900+ 年
        assertEquals(
            true,
            runtime.evalBoolean("var d = new Date(0); d.setYear(70.9); d.getUTCFullYear() === 1970"),
        )
    }

    @Test
    fun `Date 本地与 UTC setter 矩阵在固定正八偏移下与 V8 一致`() = runTest {
        val engine = Engine(DefaultHostEnvironment(timeZoneOffset = { 8L * 60 * 60 * 1000 }))
        val script = """
            function fmt(d) {
                return d.getTime() + '/' + d.getFullYear() + '-' + (d.getMonth() + 1) + '-' +
                    d.getDate() + ' ' + d.getHours() + ':' + d.getMinutes() + ':' +
                    d.getSeconds() + '.' + d.getMilliseconds();
            }
            var out = [];
            function run(label, fn) {
                try { out.push(label + '=' + fmt(fn())); }
                catch (e) { out.push(label + '=THROW:' + e.name); }
            }
            run('setHours', function () { var d = new Date(0); d.setHours(1, 2, 3, 4); return d; });
            run('setHoursOverflow', function () { var d = new Date(0); d.setHours(25); return d; });
            run('setDate', function () { var d = new Date(0); d.setDate(2); return d; });
            run('setDateOverflow', function () { var d = new Date(0); d.setDate(32); return d; });
            run('setMonth', function () { var d = new Date(0); d.setMonth(1, 29); return d; });
            run('setMonthOverflow', function () { var d = new Date(0); d.setMonth(12, 1); return d; });
            run('setFullYear', function () { var d = new Date(0); d.setFullYear(2000, 1, 29); return d; });
            run('setFullYearOverflow', function () { var d = new Date(0); d.setFullYear(2000, 12, 32); return d; });
            run('setMinutes', function () { var d = new Date(0); d.setMinutes(90, 30, 500); return d; });
            run('setSeconds', function () { var d = new Date(0); d.setSeconds(90, 500); return d; });
            run('setMilliseconds', function () { var d = new Date(0); d.setMilliseconds(1500); return d; });
            run('setYear', function () { var d = new Date(0); d.setYear(99); return d; });
            run('setTime', function () { var d = new Date(0); d.setTime(123456789); return d; });
            run('setUTCHours', function () { var d = new Date(0); d.setUTCHours(1, 2, 3, 4); return d; });
            run('setUTCMonth', function () { var d = new Date(0); d.setUTCMonth(1, 29); return d; });
            run('setUTCFullYear', function () { var d = new Date(0); d.setUTCFullYear(2000, 1, 29); return d; });
            run('setUTCDate', function () { var d = new Date(0); d.setUTCDate(32); return d; });
            run('setUTCMinutes', function () { var d = new Date(0); d.setUTCMinutes(90, 30, 500); return d; });
            run('setUTCSeconds', function () { var d = new Date(0); d.setUTCSeconds(90, 500); return d; });
            run('setUTCMilliseconds', function () { var d = new Date(0); d.setUTCMilliseconds(1500); return d; });
            out.join('\n');
        """.trimIndent()
        val expected = """
            setHours=-25076996/1970-1-1 1:2:3.4
            setHoursOverflow=61200000/1970-1-2 1:0:0.0
            setDate=86400000/1970-1-2 8:0:0.0
            setDateOverflow=2678400000/1970-2-1 8:0:0.0
            setMonth=5097600000/1970-3-1 8:0:0.0
            setMonthOverflow=31536000000/1971-1-1 8:0:0.0
            setFullYear=951782400000/2000-2-29 8:0:0.0
            setFullYearOverflow=980985600000/2001-2-1 8:0:0.0
            setMinutes=5430500/1970-1-1 9:30:30.500
            setSeconds=90500/1970-1-1 8:1:30.500
            setMilliseconds=1500/1970-1-1 8:0:1.500
            setYear=915148800000/1999-1-1 8:0:0.0
            setTime=123456789/1970-1-2 18:17:36.789
            setUTCHours=3723004/1970-1-1 9:2:3.4
            setUTCMonth=5097600000/1970-3-1 8:0:0.0
            setUTCFullYear=951782400000/2000-2-29 8:0:0.0
            setUTCDate=2678400000/1970-2-1 8:0:0.0
            setUTCMinutes=5430500/1970-1-1 9:30:30.500
            setUTCSeconds=90500/1970-1-1 8:1:30.500
            setUTCMilliseconds=1500/1970-1-1 8:0:1.500
        """.trimIndent()
        assertEquals(expected, (engine.run(script) as JSString).value)
        // V8 对 1901 年前的上海使用历史 LMT，与本宿主的固定 +08 不同；
        // 这里只校验固定偏移下的负年份换算：本地 -0001-01-01 08:00 对应 UTC 00:00
        assertEquals(
            true,
            (engine.run(
                "var d = new Date(0); d.setYear(-1); d.getUTCFullYear() === -1 && " +
                    "d.getUTCMonth() === 0 && d.getUTCDate() === 1 && " +
                    "d.getUTCHours() === 0 && d.getUTCMinutes() === 0"
            ) as JSBoolean).value,
        )
    }

    @Test
    fun `Date 本地 setter 与解析在负三时区下与 V8 一致`() = runTest {
        val engine = Engine(DefaultHostEnvironment(timeZoneOffset = { -3L * 60 * 60 * 1000 }))
        val script = """
            function fmt(d) {
                return d.getTime() + '/' + d.getFullYear() + '-' + (d.getMonth() + 1) + '-' +
                    d.getDate() + ' ' + d.getHours() + ':' + d.getMinutes() + ':' +
                    d.getSeconds() + '.' + d.getMilliseconds();
            }
            var out = [];
            function run(label, fn) {
                try { out.push(label + '=' + fmt(fn())); }
                catch (e) { out.push(label + '=THROW:' + e.name); }
            }
            run('setHours', function () { var d = new Date(0); d.setHours(1, 2, 3, 4); return d; });
            run('setHoursOverflow', function () { var d = new Date(0); d.setHours(25); return d; });
            run('setDate', function () { var d = new Date(0); d.setDate(2); return d; });
            run('setDateOverflow', function () { var d = new Date(0); d.setDate(32); return d; });
            run('setMonth', function () { var d = new Date(0); d.setMonth(1, 29); return d; });
            run('setMonthOverflow', function () { var d = new Date(0); d.setMonth(12, 1); return d; });
            run('setFullYear', function () { var d = new Date(0); d.setFullYear(2000, 1, 29); return d; });
            run('setFullYearOverflow', function () { var d = new Date(0); d.setFullYear(2000, 12, 32); return d; });
            run('setMinutes', function () { var d = new Date(0); d.setMinutes(90, 30, 500); return d; });
            run('setSeconds', function () { var d = new Date(0); d.setSeconds(90, 500); return d; });
            run('setMilliseconds', function () { var d = new Date(0); d.setMilliseconds(1500); return d; });
            run('setYear', function () { var d = new Date(0); d.setYear(99); return d; });
            run('setYearZero', function () { var d = new Date(0); d.setYear(0); return d; });
            run('setTime', function () { var d = new Date(0); d.setTime(123456789); return d; });
            run('constructor', function () { return new Date(2020, 0, 1, 12, 30, 45, 678); });
            run('constructorOverflow', function () { return new Date(2020, 12, 32, 25, 61, 61, 1001); });
            run('parseLocal', function () { return new Date(Date.parse('2020-01-01T12:30:45.678')); });
            run('parseDateOnly', function () { return new Date(Date.parse('2020-01-01')); });
            run('roundtrip', function () { return new Date(Date.parse(new Date(0).toString())); });
            out.join('\n');
        """.trimIndent()
        val expected = """
            setHours=-71876996/1969-12-31 1:2:3.4
            setHoursOverflow=14400000/1970-1-1 1:0:0.0
            setDate=-2505600000/1969-12-2 21:0:0.0
            setDateOverflow=86400000/1970-1-1 21:0:0.0
            setMonth=-26352000000/1969-3-1 21:0:0.0
            setMonthOverflow=86400000/1970-1-1 21:0:0.0
            setFullYear=951868800000/2000-2-29 21:0:0.0
            setFullYearOverflow=981072000000/2001-2-1 21:0:0.0
            setMinutes=5430500/1969-12-31 22:30:30.500
            setSeconds=90500/1969-12-31 21:1:30.500
            setMilliseconds=1500/1969-12-31 21:0:1.500
            setYear=946684800000/1999-12-31 21:0:0.0
            setYearZero=-2177452800000/1900-12-31 21:0:0.0
            setTime=123456789/1970-1-2 7:17:36.789
            constructor=1577892645678/2020-1-1 12:30:45.678
            constructorOverflow=1612242122001/2021-2-2 2:2:2.1
            parseLocal=1577892645678/2020-1-1 12:30:45.678
            parseDateOnly=1577836800000/2019-12-31 21:0:0.0
            roundtrip=0/1969-12-31 21:0:0.0
        """.trimIndent()
        assertEquals(expected, (engine.run(script) as JSString).value)
    }

    @Test
    fun `Date 字段 setter 在 NaN 参数下仍完成其余参数转换`() = runTest {
        val runtime = TestRuntime()
        val prefix = "var log = []; function arg(n) { return { valueOf: function () { " +
            "log.push(n); return 1; } }; } "
        assertEquals(
            "day",
            runtime.evalString(prefix + "new Date(0).setMonth(NaN, arg('day')); log.join(',')"),
        )
        assertEquals(
            "m,d",
            runtime.evalString(prefix + "new Date(0).setFullYear(NaN, arg('m'), arg('d')); log.join(',')"),
        )
        assertEquals(
            "m,s,ms",
            runtime.evalString(
                prefix + "new Date(0).setHours(NaN, arg('m'), arg('s'), arg('ms')); log.join(',')"
            ),
        )
        assertEquals(
            "s,ms",
            runtime.evalString(prefix + "new Date(0).setMinutes(NaN, arg('s'), arg('ms')); log.join(',')"),
        )
        assertEquals(
            "ms",
            runtime.evalString(prefix + "new Date(0).setSeconds(NaN, arg('ms')); log.join(',')"),
        )
        assertEquals(
            "day",
            runtime.evalString(prefix + "new Date(0).setUTCMonth(NaN, arg('day')); log.join(',')"),
        )
    }
}
