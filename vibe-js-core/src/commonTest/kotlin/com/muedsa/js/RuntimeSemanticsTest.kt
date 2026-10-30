package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 运行时语义边界：TDZ、作用域、this、枚举顺序、类型转换、try/finally、JSON 等。
 */
class RuntimeSemanticsTest {

    // ================================================================
    // 暂时性死区（TDZ）
    // ================================================================

    @Test
    fun `let 在声明前读取或 typeof 抛 ReferenceError`() = runTest {
        val runtime = TestRuntime()
        val typeofError = assertFailsWith<JSException> {
            runtime.eval("typeof x; let x = 1;")
        }
        assertEquals("ReferenceError", (typeofError.value as JSError).errorName)

        val readError = assertFailsWith<JSException> {
            runtime.eval("y; let y = 1;")
        }
        assertEquals("ReferenceError", (readError.value as JSError).errorName)

        val blockError = assertFailsWith<JSException> {
            runtime.eval("{ typeof z; let z = 1; }")
        }
        assertEquals("ReferenceError", (blockError.value as JSError).errorName)
    }

    @Test
    fun `let 自引用与类声明的 TDZ`() = runTest {
        val runtime = TestRuntime()
        val selfReference = assertFailsWith<JSException> { runtime.eval("let a = a;") }
        assertEquals("ReferenceError", (selfReference.value as JSError).errorName)

        val classError = assertFailsWith<JSException> { runtime.eval("typeof C; class C {}") }
        assertEquals("ReferenceError", (classError.value as JSError).errorName)
    }

    @Test
    fun `闭包在 let 初始化前访问抛 ReferenceError`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "ReferenceError",
            runtime.evalString(
                """
                var result = '';
                function readLater() { return later; }
                try { readLater(); } catch (e) { result = e.name; }
                let later = 1;
                result;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `同一作用域重复声明词法变量抛 SyntaxError`() = runTest {
        val runtime = TestRuntime()
        val blockError = assertFailsWith<JSException> {
            runtime.eval("{ let a = 1; let a = 2; }")
        }
        assertEquals("SyntaxError", (blockError.value as JSError).errorName)

        val constError = assertFailsWith<JSException> {
            runtime.eval("{ const b = 1; const b = 2; }")
        }
        assertEquals("SyntaxError", (constError.value as JSError).errorName)

        runtime.eval("let once = 1;")
        val globalError = assertFailsWith<JSException> { runtime.eval("let once = 2;") }
        assertEquals("SyntaxError", (globalError.value as JSError).errorName)
    }

    @Test
    fun `let 循环每次迭代独立绑定`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "0,1,2",
            runtime.evalString(
                """
                var callbacks = [];
                for (let i = 0; i < 3; i++) {
                    callbacks.push(function () { return i; });
                }
                callbacks[0]() + ',' + callbacks[1]() + ',' + callbacks[2]();
                """.trimIndent()
            )
        )
        assertEquals(
            "undefined",
            runtime.evalString(
                """
                for (let i = 0; i < 3; i++) { }
                typeof i;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `const 绑定不可重新赋值但对象内容可修改`() = runTest {
        val runtime = TestRuntime()
        assertEquals(2.0, runtime.evalNumber("const o = { a: 1 }; o.a = 2; o.a"))
        assertEquals(1.0, runtime.evalNumber("const a = [1]; a.push(2); a.length - 1"))
        val error = assertFailsWith<JSException> { runtime.eval("const x = 1; x = 2;") }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    // ================================================================
    // 函数、this 与 arguments
    // ================================================================

    @Test
    fun `arguments 不是数组且缺失参数为 undefined`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "1:false:undefined",
            runtime.evalString(
                "function f(a, b) { return arguments.length + ':' + Array.isArray(arguments) + ':' + b; } f(1)"
            )
        )
        assertEquals(
            "0",
            runtime.evalString("function g(a = 1) { return '' + arguments.length; } g()")
        )
        assertEquals(3.0, runtime.evalNumber("function f(a) { return arguments.length; } f(1, 2, 3)"))
    }

    @Test
    fun `严格与非严格模式下 call 的 this 绑定`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean("function f() { 'use strict'; return this === null; } f.call(null)")
        )
        assertEquals(
            true,
            runtime.evalBoolean("function f() { return this === globalThis; } f.call(null)")
        )
        assertEquals(
            1.0,
            runtime.evalNumber("var o = { x: 1 }; function f() { return this.x; } f.call(o)")
        )
        assertEquals(
            2.0,
            runtime.evalNumber("function f(a, b) { return this.x + a + b; } f.apply({ x: 0 }, [1, 1])")
        )
    }

    // ================================================================
    // 属性枚举与描述符
    // ================================================================

    @Test
    fun `属性枚举顺序为索引升序再插入顺序`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "1,2,10,b,a",
            runtime.evalString(
                """
                var o = {};
                o.b = 1;
                o[2] = 2;
                o[1] = 3;
                o[10] = 4;
                o.a = 5;
                Object.keys(o).join(',');
                """.trimIndent()
            )
        )
        assertEquals(
            "1,2,10,b",
            runtime.evalString("var o = { b: 1, 2: 2, 1: 3, 10: 4 }; Object.getOwnPropertyNames(o).join(',')")
        )
    }

    @Test
    fun `for-in 跳过不可枚举属性与 Symbol 键`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "a",
            runtime.evalString(
                """
                var o = { a: 1 };
                Object.defineProperty(o, 'b', { value: 2, enumerable: false });
                o[Symbol('s')] = 3;
                var result = '';
                for (var k in o) { result += k; }
                result;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `数组索引与 length 的属性描述符`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "1:true:true:true",
            runtime.evalString(
                """
                var a = [1];
                var d = Object.getOwnPropertyDescriptor(a, '0');
                d.value + ':' + d.writable + ':' + d.enumerable + ':' + d.configurable;
                """.trimIndent()
            )
        )
        assertEquals(
            "1:true:false:false",
            runtime.evalString(
                """
                var a = [1];
                var d = Object.getOwnPropertyDescriptor(a, 'length');
                d.value + ':' + d.writable + ':' + d.enumerable + ':' + d.configurable;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `不可配置属性不能被删除`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "false:1",
            runtime.evalString(
                """
                var o = {};
                Object.defineProperty(o, 'x', { value: 1, configurable: false });
                (delete o.x) + ':' + o.x;
                """.trimIndent()
            )
        )
        val error = assertFailsWith<JSException> {
            runtime.eval(
                """
                'use strict';
                var o = {};
                Object.defineProperty(o, 'x', { value: 1, configurable: false });
                delete o.x;
                """.trimIndent()
            )
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `数组 length 扩展与索引写入`() = runTest {
        val runtime = TestRuntime()
        assertEquals("3:undefined", runtime.evalString("var a = [1]; a.length = 3; a.length + ':' + a[2]"))
        assertEquals(11.0, runtime.evalNumber("var a = []; a[10] = 'x'; a.length"))
        assertEquals("10", runtime.evalString("var a = []; a[10] = 'x'; Object.keys(a).join(',')"))
        assertEquals("1", runtime.evalString("var a = [1, 2, 3]; a.length = 1; a.join(',')"))
    }

    // ================================================================
    // 类型转换与运算
    // ================================================================

    @Test
    fun `一元加号与对象到原始值转换`() = runTest {
        val runtime = TestRuntime()
        assertEquals(0.0, runtime.evalNumber("+[]"))
        assertEquals(1.0, runtime.evalNumber("+[1]"))
        assertTrue(runtime.evalNumber("+[1, 2]").isNaN())
        assertEquals(12.0, runtime.evalNumber("+'  12  '"))
        assertEquals(0.0, runtime.evalNumber("+null"))
        assertTrue(runtime.evalNumber("+undefined").isNaN())
        assertEquals(1.0, runtime.evalNumber("+true"))
    }

    @Test
    fun `加法与抽象相等转换`() = runTest {
        val runtime = TestRuntime()
        assertEquals("[object Object]", runtime.evalString("'' + {}"))
        assertEquals("1,2", runtime.evalString("'' + [1, 2]"))
        assertEquals(1.0, runtime.evalNumber("1 + null"))
        assertTrue(runtime.evalNumber("1 + undefined").isNaN())
        assertEquals(true, runtime.evalBoolean("null == undefined"))
        assertEquals(false, runtime.evalBoolean("null == 0"))
        assertEquals(true, runtime.evalBoolean("0 == ''"))
        assertEquals(true, runtime.evalBoolean("'0' == false"))
        assertEquals(true, runtime.evalBoolean("[1] == 1"))
        assertEquals(true, runtime.evalBoolean("[1, 2] == '1,2'"))
        assertEquals(true, runtime.evalBoolean("[] == false"))
        assertEquals(false, runtime.evalBoolean("NaN == NaN"))
        assertEquals(true, runtime.evalBoolean("null >= 0"))
        assertEquals(true, runtime.evalBoolean("'2' > 1"))
        assertEquals(true, runtime.evalBoolean("'10' < '9'"))
    }

    @Test
    fun `位运算与移位`() = runTest {
        val runtime = TestRuntime()
        assertEquals(-1.0, runtime.evalNumber("~0"))
        assertEquals(1.0, runtime.evalNumber("5 & 3"))
        assertEquals(7.0, runtime.evalNumber("5 | 2"))
        assertEquals(4.0, runtime.evalNumber("5 ^ 1"))
        assertEquals(-2147483648.0, runtime.evalNumber("1 << 31"))
        assertEquals(1.0, runtime.evalNumber("1 << 32"))
        assertEquals(-1.0, runtime.evalNumber("-1 >> 1"))
        assertEquals(4294967295.0, runtime.evalNumber("-1 >>> 0"))
        assertEquals(2147483647.0, runtime.evalNumber("-1 >>> 1"))
    }

    @Test
    fun `逗号与条件运算符`() = runTest {
        val runtime = TestRuntime()
        assertEquals(2.0, runtime.evalNumber("1, 2"))
        assertEquals("a", runtime.evalString("true ? 'a' : 'b'"))
        assertEquals(2.0, runtime.evalNumber("var x = 0; x = (x++, x + 1); x"))
    }

    // ================================================================
    // try / finally
    // ================================================================

    @Test
    fun `finally 在 return 时执行且可以覆盖返回值`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "t:f",
            runtime.evalString(
                """
                var order = '';
                function f() {
                    try { return 't'; } finally { order += 'f'; }
                }
                f() + ':' + order;
                """.trimIndent()
            )
        )
        assertEquals(
            2.0,
            runtime.evalNumber("function g() { try { return 1; } finally { return 2; } } g()")
        )
        assertEquals(
            "cxf",
            runtime.evalString(
                """
                var result = '';
                try {
                    throw 'x';
                } catch (e) {
                    result += 'c' + e;
                } finally {
                    result += 'f';
                }
                result;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `finally 中抛错会覆盖原始返回值`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> {
            runtime.eval(
                "function f() { try { return 1; } finally { throw new Error('finally'); } } f()"
            )
        }
        assertEquals("finally", (error.value as JSError).messageText)
    }

    // ================================================================
    // 原始值属性
    // ================================================================

    @Test
    fun `原始值可以读取属性与方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("'abc'.length"))
        assertEquals("b", runtime.evalString("'abc'[1]"))
        assertEquals("undefined", runtime.evalString("'' + 'abc'[5]"))
        assertEquals("5", runtime.evalString("(5).toString()"))
        assertEquals("true", runtime.evalString("true.toString()"))
        assertEquals(1.0, runtime.evalNumber("'abc'.indexOf('b')"))
    }

    // ================================================================
    // JSON 边界
    // ================================================================

    @Test
    fun `JSON_stringify 忽略 undefined 与函数`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "{\"a\":1}",
            runtime.evalString("JSON.stringify({ a: 1, b: undefined })")
        )
        assertEquals(
            "[1,null,null]",
            runtime.evalString("JSON.stringify([1, undefined, function () {}])")
        )
        assertEquals(
            "{\"a\":1}",
            runtime.evalString(
                "JSON.stringify({ a: 1, b: 2 }, function (k, v) { return k === 'b' ? undefined : v; })"
            )
        )
    }

    @Test
    fun `JSON_stringify 字符串转义与往返`() = runTest {
        val runtime = TestRuntime()
        assertEquals("\"a\\\"b\"", runtime.evalString("JSON.stringify('a\"b')"))
        assertEquals("\"a\\\\b\"", runtime.evalString("JSON.stringify('a\\\\b')"))
        assertEquals("\"\\u0001\"", runtime.evalString("JSON.stringify('\\u0001')"))
        assertEquals(
            "a\"b\nc",
            runtime.evalString("JSON.parse(JSON.stringify({ s: 'a\"b\\nc' })).s")
        )
    }

    @Test
    fun `JSON_parse 的重复键与语法错误`() = runTest {
        val runtime = TestRuntime()
        assertEquals(2.0, runtime.evalNumber("JSON.parse('{\"a\":1,\"a\":2}').a"))
        val error = assertFailsWith<JSException> { runtime.eval("JSON.parse('{')") }
        assertEquals("SyntaxError", (error.value as JSError).errorName)
    }
}
