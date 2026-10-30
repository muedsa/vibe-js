package com.muedsa.js

import com.muedsa.js.parser.ParseException
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 阶段 B 新增语法与内建测试。
 */
class PhaseBTest {

    @Test
    fun `do-while 循环`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("var i = 0; do { i++; } while (i < 3); i"))
        assertEquals(1.0, runtime.evalNumber("var i = 0; do { i++; } while (false); i"))
    }

    @Test
    fun `for-in 遍历自身与继承属性`() = runTest {
        val runtime = TestRuntime()
        assertEquals("ab", runtime.evalString("var o = { a: 1, b: 2 }; var s = ''; for (var k in o) { s += k; } s"))
        assertEquals(
            "az",
            runtime.evalString(
                "var proto = { z: 1 }; var o = Object.create(proto); o.a = 2; var s = ''; for (var k in o) { s += k; } s"
            )
        )
        assertEquals(
            "a",
            runtime.evalString("var o = { a: 1 }; var s = ''; for (k in o) { s += k; } s")
        )
    }

    @Test
    fun `标签语句与带标签的循环跳转`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "001020",
            runtime.evalString(
                """
                var s = "";
                outer: for (var i = 0; i < 3; i++) {
                    for (var j = 0; j < 3; j++) {
                        if (j === 1) { continue outer; }
                        s += "" + i + j;
                    }
                }
                s;
                """.trimIndent()
            )
        )
        assertEquals(
            5.0,
            runtime.evalNumber(
                "var n = 0; outer: for (var i = 0; i < 5; i++) { for (var j = 0; j < 5; j++) { if (i === 1) { break outer; } n++; } } n"
            )
        )
    }

    @Test
    fun `delete 运算符`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("var o = { a: 1 }; delete o.a; o.a === undefined"))
        assertEquals(0.0, runtime.evalNumber("var o = { a: 1 }; delete o['a']; Object.keys(o).length"))
        assertEquals(
            "3:false:undefined",
            runtime.evalString("var a = [1, 2, 3]; delete a[1]; a.length + ':' + (1 in a) + ':' + a[1]")
        )
        assertEquals(false, runtime.evalBoolean("var x = 1; delete x"))
        assertEquals(1.0, runtime.evalNumber("var x = 1; delete x; x"))
    }

    @Test
    fun `void 与 in 运算符`() = runTest {
        val runtime = TestRuntime()
        assertEquals("undefined", runtime.evalString("typeof void 0"))
        assertEquals(true, runtime.evalBoolean("'a' in { a: 1 }"))
        assertEquals(true, runtime.evalBoolean("'toString' in {}"))
        assertEquals(true, runtime.evalBoolean("0 in [1]"))
        assertEquals(false, runtime.evalBoolean("1 in [1]"))
    }

    @Test
    fun `instanceof 运算符`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("function F() {}; var f = new F(); f instanceof F"))
        assertEquals(true, runtime.evalBoolean("function F() {}; new F() instanceof Object"))
        assertEquals(true, runtime.evalBoolean("[] instanceof Array"))
        assertEquals(false, runtime.evalBoolean("({}) instanceof Array"))
        assertFailsWith<JSException> { runtime.eval("1 instanceof 2") }
    }

    @Test
    fun `对象字面量简写方法访问器与计算键`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("var a = 1; var o = { a }; o.a"))
        assertEquals(2.0, runtime.evalNumber("var o = { f() { return 2; } }; o.f()"))
        assertEquals(
            5.0,
            runtime.evalNumber("var o = { get x() { return 5; }, set x(v) { this.y = v; } }; o.x")
        )
        assertEquals(
            7.0,
            runtime.evalNumber(
                "var o = { get x() { return 5; }, set x(v) { this.y = v; } }; o.x = 7; o.y"
            )
        )
        assertEquals(1.0, runtime.evalNumber("var k = 'a'; var o = { [k]: 1 }; o.a"))
    }

    @Test
    fun `逻辑赋值与幂赋值`() = runTest {
        val runtime = TestRuntime()
        assertEquals(5.0, runtime.evalNumber("var x = 0; x ||= 5; x"))
        assertEquals(1.0, runtime.evalNumber("var x = 1; x ||= 5; x"))
        assertEquals(3.0, runtime.evalNumber("var y = 1; y &&= 3; y"))
        assertEquals(0.0, runtime.evalNumber("var y = 0; y &&= 3; y"))
        assertEquals(4.0, runtime.evalNumber("var z = null; z ??= 4; z"))
        assertEquals(2.0, runtime.evalNumber("var z = 2; z ??= 4; z"))
        assertEquals(8.0, runtime.evalNumber("var p = 2; p **= 3; p"))
    }

    @Test
    fun `正则字面量与 exec`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("var r = /ab+/i; r.test('ABBB')"))
        assertEquals(
            "12,34,1",
            runtime.evalString("var m = /(\\d+)-(\\d+)/.exec('x12-34y'); m[1] + ',' + m[2] + ',' + m.index")
        )
        assertEquals(
            "0:1:2:3",
            runtime.evalString(
                "var r = /a/g; var a = r.exec('aba'); var i1 = r.lastIndex; var b = r.exec('aba'); var i2 = r.lastIndex; a.index + ':' + i1 + ':' + b.index + ':' + i2"
            )
        )
        assertEquals(true, runtime.evalBoolean("new RegExp('ab+').test('abb')"))
        assertEquals("/ab+/g", runtime.evalString("String(new RegExp('ab+', 'g'))"))
    }

    @Test
    fun `字符串正则方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1,2", runtime.evalString("'a1b2'.match(/\\d/g).join(',')"))
        assertEquals(1.0, runtime.evalNumber("'abc'.search(/b/)"))
        assertEquals("a#b#", runtime.evalString("'a1b2'.replace(/\\d/g, '#')"))
        assertEquals("a[b]c", runtime.evalString("'abc'.replace(/(b)/, '[$1]')"))
        assertEquals("aBc", runtime.evalString("'abc'.replace(/b/, function (m) { return m.toUpperCase(); })"))
        assertEquals("a,b,", runtime.evalString("'a1b2'.split(/\\d/).join(',')"))
    }

    @Test
    fun `模板字符串`() = runTest {
        val runtime = TestRuntime()
        assertEquals("hi x", runtime.evalString("var name = 'x'; `hi \${name}`"))
        assertEquals("3", runtime.evalString("`\${1 + 2}`"))
        assertEquals("a1b", runtime.evalString("var a = 1; `a\${a}b`"))
    }

    @Test
    fun `Date 构造与 UTC 方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1970-01-01T00:00:00.000Z", runtime.evalString("new Date(0).toISOString()"))
        assertEquals(0.0, runtime.evalNumber("new Date(0).getTime()"))
        assertEquals(2020.0, runtime.evalNumber("new Date(Date.UTC(2020, 0, 2)).getUTCFullYear()"))
        assertEquals(0.0, runtime.evalNumber("new Date(Date.UTC(2020, 0, 2)).getUTCMonth()"))
        assertEquals(2.0, runtime.evalNumber("new Date(Date.UTC(2020, 0, 2)).getUTCDate()"))
        assertEquals(2020.0, runtime.evalNumber("new Date(2020, 0, 1).getFullYear()"))
        assertEquals(
            1577836800000.0,
            runtime.evalNumber("Date.parse('2020-01-01T00:00:00Z')")
        )
    }

    @Test
    fun `arguments 对象`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "2:1:f",
            runtime.evalString(
                "function f(a, b) { return arguments.length + ':' + arguments[0] + ':' + arguments.callee.name; } f(1, 2)"
            )
        )
    }

    @Test
    fun `盒装原始值`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "object:3:ABC",
            runtime.evalString("var s = new String('abc'); typeof s + ':' + s.length + ':' + s.toUpperCase()")
        )
        assertEquals(2.0, runtime.evalNumber("new Number(2).valueOf()"))
        assertEquals(false, runtime.evalBoolean("new Boolean(false).valueOf()"))
        assertEquals("abc", runtime.evalString("String(new String('abc'))"))
    }

    @Test
    fun `Object_freeze 与 seal`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("var o = { a: 1 }; Object.freeze(o); o.a = 2; o.a"))
        assertEquals(true, runtime.evalBoolean("var o = { a: 1 }; Object.freeze(o); Object.isFrozen(o)"))
        assertEquals(false, runtime.evalBoolean("var o = { a: 1 }; Object.isFrozen(o)"))
        assertEquals(
            "a",
            runtime.evalString("var o = { a: 1 }; Object.seal(o); o.b = 2; Object.keys(o).join(',')")
        )
        val error = assertFailsWith<JSException> {
            runtime.eval("\"use strict\"; var o = { a: 1 }; Object.freeze(o); o.a = 2;")
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `Function 构造器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("var add = new Function('a', 'b', 'return a + b;'); add(1, 2)"))
        assertEquals(2.0, runtime.evalNumber("var f = Function('return 1 + 1;'); f()"))
    }

    @Test
    fun `数字指数与精度格式`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1.23e+2", runtime.evalString("(123.456).toExponential(2)"))
        assertEquals("123.5", runtime.evalString("(123.456).toPrecision(4)"))
        assertEquals("1.2e-7", runtime.evalString("(0.000000123).toPrecision(2)"))
    }

    @Test
    fun `自动分号插入与语法错误`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("var a = 1\nvar b = 2\na + b"))
        assertFailsWith<ParseException> { runtime.eval("var a = 1 var b = 2") }
        assertFailsWith<ParseException> { runtime.eval("throw\n1") }
    }

    @Test
    fun `debugger 语句`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("debugger; 1"))
    }

    @Test
    fun `with 语句`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            3.0,
            runtime.evalNumber("var o = { a: 1, b: 2 }; var r = 0; with (o) { r = a + b; } r")
        )
        assertEquals(
            5.0,
            runtime.evalNumber("var o = { x: 5 }; var x = 1; with (o) { x; }")
        )
        assertFailsWith<ParseException> {
            runtime.eval("\"use strict\"; with ({}) {}")
        }
    }

    @Test
    fun `Date 方法在非 Date 接收者上抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "TypeError",
            runtime.evalString(
                "var r = ''; try { Date.prototype.getTime.call({}); } catch (e) { r = e.name; } r"
            )
        )
    }
}
