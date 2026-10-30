package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ES2015 语法之间的交互语义：自定义迭代解构、对象剩余/展开、类方法与正则替换回调。
 */
class Es2015SemanticsTest {

    @Test
    fun `解构支持自定义迭代器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "3:3",
            runtime.evalString(
                """
                var iterable = {};
                iterable[Symbol.iterator] = function () {
                    var index = 0;
                    var values = [1, 2, 3];
                    return {
                        next: function () {
                            return index < values.length
                                ? { value: values[index++], done: false }
                                : { done: true };
                        }
                    };
                };
                var [a, b, ...rest] = iterable;
                a + b + ':' + rest.join(',');
                """.trimIndent()
            )
        )
        // a=1、b=2，剩余参数为 [3]
        assertEquals(
            "x:y",
            runtime.evalString("var [a, b] = 'xy'; a + ':' + b")
        )
    }

    @Test
    fun `对象解构支持原始值装箱`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("var { length } = 'abc'; length"))
        assertEquals(2.0, runtime.evalNumber("var { 0: first, 1: second } = 'ab'; first.length + second.length"))
    }

    @Test
    fun `对象剩余与展开保留 Symbol 键`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "1:2",
            runtime.evalString(
                """
                var s = Symbol('s');
                var source = {};
                source[s] = 1;
                source.a = 2;
                var { ...rest } = source;
                rest[s] + ':' + rest.a;
                """.trimIndent()
            )
        )
        assertEquals(
            3.0,
            runtime.evalNumber(
                "var s = Symbol('s'); var source = {}; source[s] = 1; source.a = 2; var copy = { ...source }; copy[s] + copy.a"
            )
        )
        assertEquals(
            false,
            runtime.evalBoolean("var { a, ...rest } = { a: 1, b: 2 }; 'a' in rest")
        )
    }

    @Test
    fun `类支持计算属性名与 async 方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            1.0,
            runtime.evalNumber("var key = 'm'; class C { [key]() { return 1; } } new C().m()")
        )
        runtime.eval("var r = 0; class D { async m() { return 3; } } new D().m().then(function (v) { r = v; });")
        assertEquals(3.0, runtime.evalNumber("r"))
        runtime.eval("var o = { async m() { return 4; } }; var objResult = 0; o.m().then(function (v) { objResult = v; });")
        assertEquals(4.0, runtime.evalNumber("objResult"))
    }

    @Test
    fun `静态方法中的 super 调用`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            5.0,
            runtime.evalNumber(
                "class A { static f() { return 5; } } class B extends A { static g() { return super.f(); } } B.g()"
            )
        )
    }

    @Test
    fun `类构造器返回对象时覆盖实例`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            7.0,
            runtime.evalNumber("class A { constructor() { return { x: 7 }; } } new A().x")
        )
        assertEquals(
            1.0,
            runtime.evalNumber("class B { constructor() { this.x = 1; return 2; } } new B().x")
        )
    }

    @Test
    fun `标签模板保留原始字符串`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "a\\nb",
            runtime.evalString(
                """
                function tag(strings) { return strings.raw[0]; }
                tag`a\nb`;
                """.trimIndent()
            )
        )
        assertEquals(
            "a\nb",
            runtime.evalString(
                """
                function tag(strings) { return strings[0]; }
                tag`a\nb`;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `正则替换回调接收捕获组与位置参数`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "abb1abcc",
            runtime.evalString(
                "'abc'.replace(/(b)/, function (match, p1, offset, input) { return match + p1 + offset + input; })"
            )
        )
        assertEquals(
            "a[1]b",
            runtime.evalString("'a1b'.replace(/\\d/, '[\$&]')")
        )
        assertEquals(
            "a[a]b",
            runtime.evalString("'a1b'.replace(/\\d/, '[\$`]')")
        )
        assertEquals(
            "a[b]b",
            runtime.evalString("'a1b'.replace(/\\d/, \"[\$']\")")
        )
        assertEquals(
            "a\$b",
            runtime.evalString("'a1b'.replace(/\\d/, '\$\$')")
        )
    }

    @Test
    fun `replaceAll 限制与空分隔符`() = runTest {
        val runtime = TestRuntime()
        assertEquals("-a-b-c-", runtime.evalString("'abc'.replaceAll('', '-')"))
        assertEquals("a-b-c", runtime.evalString("'a1b2c'.replaceAll(/\\d/g, '-')"))
        val error = assertFailsWith<JSException> {
            runtime.eval("'aa'.replaceAll(/a/, 'b')")
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `String_match 与 search 的未命中行为`() = runTest {
        val runtime = TestRuntime()
        assertEquals("null", runtime.evalString("'' + 'abc'.match(/x/)"))
        assertEquals(-1.0, runtime.evalNumber("'abc'.search(/x/)"))
        assertEquals(2.0, runtime.evalNumber("'a1b2'.match(/\\d/g).length"))
        assertEquals(
            "1",
            runtime.evalString("'a1b'.match(/\\d/)[0]")
        )
    }

    @Test
    fun `Object_defineProperty 在不可扩展对象上抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> {
            runtime.eval(
                "var o = {}; Object.preventExtensions(o); Object.defineProperty(o, 'x', { value: 1 });"
            )
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `Date 闰年与日期归一化`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "2020-02-29T00:00:00.000Z",
            runtime.evalString("new Date(Date.UTC(2020, 1, 29)).toISOString()")
        )
        assertEquals(
            "2019-03-01T00:00:00.000Z",
            runtime.evalString("new Date(Date.UTC(2019, 1, 29)).toISOString()")
        )
        assertEquals(
            "2020-12-31T00:00:00.000Z",
            runtime.evalString("new Date(Date.UTC(2020, 11, 31)).toISOString()")
        )
    }
}
