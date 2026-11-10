package com.muedsa.js

import com.muedsa.js.parser.ParseException
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.value.JSNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * 基础执行能力：字面量、运算、变量、函数、闭包、控制流。
 */
class BasicExecutionTest {

    @Test
    fun `算术运算与优先级`() = runTest {
        val runtime = TestRuntime()
        assertEquals(7.0, runtime.evalNumber("1 + 2 * 3"))
        assertEquals(8.0, runtime.evalNumber("2 ** 3"))
        assertEquals(1.0, runtime.evalNumber("7 % 3"))
        assertEquals(-1.0, runtime.evalNumber("1 - 2"))
    }

    @Test
    fun `字符串拼接`() = runTest {
        val runtime = TestRuntime()
        assertEquals("a1", runtime.evalString("'a' + 1"))
        assertEquals("12", runtime.evalString("1 + '2'"))
        assertEquals("1[object Object]", runtime.evalString("1 + {}"))
        assertEquals("1,23", runtime.evalString("[1, 2] + [3]"))
    }

    @Test
    fun `变量声明与作用域`() = runTest {
        val runtime = TestRuntime()
        assertEquals(6.0, runtime.evalNumber("var a = 1; let b = 2; const c = 3; a + b + c"))
        // 同一全局环境中 var a 已存在，新脚本再声明 let a 属于 SyntaxError，故改用其它名字
        assertEquals(1.0, runtime.evalNumber("let d = 1; { let d = 2; } d"))
        assertEquals(2.0, runtime.evalNumber("var x = 1; { var x = 2; } x"))
    }

    @Test
    fun `函数声明与闭包`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            3.0,
            runtime.evalNumber(
                "function add(a, b) { return a + b; } add(1, 2)"
            )
        )
        assertEquals(
            3.0,
            runtime.evalNumber(
                "function outer(x) { return function (y) { return x + y; }; } outer(1)(2)"
            )
        )
        assertEquals(
            120.0,
            runtime.evalNumber(
                "function fact(n) { if (n <= 1) { return 1; } return n * fact(n - 1); } fact(5)"
            )
        )
    }

    @Test
    fun `变量提升`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("var r = f(); function f() { return 1; } r"))
        assertEquals("undefined", runtime.evalString("typeof missingVariable"))
    }

    @Test
    fun `循环与跳转`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            10.0,
            runtime.evalNumber("var sum = 0; for (var i = 0; i <= 4; i++) { sum += i; } sum")
        )
        assertEquals(
            3.0,
            runtime.evalNumber(
                "var count = 0; for (var i = 0; i < 5; i++) { if (i === 2) { continue; } if (i === 4) { break; } count++; } count"
            )
        )
        assertEquals(
            6.0,
            runtime.evalNumber(
                "var sum = 0; var i = 0; while (i < 4) { sum += i; i++; } sum"
            )
        )
    }

    @Test
    fun `switch 的 default 位置不影响匹配`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "two",
            runtime.evalString(
                """
                var r = "";
                switch (2) {
                    default: r = "default"; break;
                    case 2: r = "two"; break;
                }
                r;
                """.trimIndent()
            )
        )
        assertEquals(
            "fallthrough",
            runtime.evalString(
                """
                var r = "";
                switch (1) {
                    case 1: r += "fall";
                    case 2: r += "through"; break;
                }
                r;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `空语句与自动分号`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("function f() {}; var a = 1; a"))
        assertEquals(
            "undefined",
            runtime.evalString(
                """
                function f() {
                  return
                  1;
                }
                typeof f()
                """.trimIndent()
            )
        )
    }

    @Test
    fun `逻辑运算短路`() = runTest {
        val runtime = TestRuntime()
        assertEquals("x", runtime.evalString("0 || 'x'"))
        assertEquals(2.0, runtime.evalNumber("1 && 2"))
        assertEquals(1.0, runtime.evalNumber("null ?? 1"))
        assertEquals(0.0, runtime.evalNumber("0 ?? 1"))
    }

    @Test
    fun `类型判断与相等`() = runTest {
        val runtime = TestRuntime()
        assertEquals("object", runtime.evalString("typeof []"))
        assertEquals("object", runtime.evalString("typeof null"))
        assertEquals("function", runtime.evalString("typeof function () {}"))
        assertEquals("undefined", runtime.evalString("typeof notDefinedAnywhere"))
        assertEquals(false, runtime.evalBoolean("NaN === NaN"))
        assertEquals(true, runtime.evalBoolean("null == undefined"))
        assertEquals(false, runtime.evalBoolean("null === undefined"))
    }

    @Test
    fun `catch 未声明标识符之后的继续执行`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "ReferenceError",
            runtime.evalString("var n = ''; try { x; } catch (e) { n = e.name; } n")
        )
    }

    @Test
    fun `预解析脚本可重复执行并共享全局环境`() = runTest {
        val runtime = TestRuntime()
        val counter = runtime.engine.parse(
            "var count = (typeof count === 'undefined' ? 0 : count) + 1; count"
        )
        assertEquals(1.0, (runtime.engine.run(counter) as JSNumber).value)
        assertEquals(2.0, (runtime.engine.run(counter) as JSNumber).value)
        val call = runtime.engine.parse("function twice(x) { return x * 2; } twice(21)")
        assertEquals(42.0, (runtime.engine.run(call) as JSNumber).value)
        assertEquals(42.0, (runtime.engine.run(call) as JSNumber).value)
        // 解析阶段不接触全局环境：声明实例化只在执行时发生
        val lazy = runtime.engine.parse("var lazyBinding = 1;")
        assertEquals(true, runtime.evalBoolean("typeof lazyBinding === 'undefined'"))
        runtime.engine.run(lazy)
        assertEquals(1.0, runtime.evalNumber("lazyBinding"))
        // 同一 AST 的标签模板站点在多次执行间复用缓存对象
        val tagged = runtime.engine.parse("function tag(s) { return s; } tag`x`")
        val firstTemplate = runtime.engine.run(tagged)
        val secondTemplate = runtime.engine.run(tagged)
        assertSame(firstTemplate, secondTemplate)
        // 与 run(source) 一致：脚本结束后冲刷微任务
        val asyncProgram = runtime.engine.parse(
            "Promise.resolve(1).then(function () { globalThis.parsedAsync = 2; });"
        )
        runtime.engine.run(asyncProgram)
        assertEquals(2.0, runtime.evalNumber("parsedAsync"))
    }

    @Test
    fun `可选解析缓存复用相同源码并保持标签模板语义`() = runTest {
        val engine = Engine(parseCacheSize = 2)
        val counter = "var cachedRunCount = (cachedRunCount || 0) + 1; cachedRunCount"
        assertEquals(1.0, (engine.run(counter) as JSNumber).value)
        assertEquals(2.0, (engine.run(counter) as JSNumber).value)
        // 含标签模板的源码不缓存，站点对象仍按每次解析隔离
        val tagged = "function tag(s) { return s; } tag`x`"
        assertNotSame(engine.run(tagged), engine.run(tagged))
    }

    @Test
    fun `解析缓存按容量淘汰且不缓存语法错误`() = runTest {
        assertFailsWith<IllegalArgumentException> { Engine(parseCacheSize = -1) }
        val engine = Engine(parseCacheSize = 1)
        assertFailsWith<ParseException> { engine.run("var =") }
        assertFailsWith<ParseException> { engine.run("var =") }
        assertEquals(1.0, (engine.run("1") as JSNumber).value)
        assertEquals(2.0, (engine.run("2") as JSNumber).value)
        assertEquals(1.0, (engine.run("1") as JSNumber).value)
    }

    @Test
    fun `开启解析缓存后并发 run 仍串行执行`() = runTest {
        val engine = Engine(parseCacheSize = 4)
        val source = "globalThis.cachedConcurrent = (globalThis.cachedConcurrent || 0) + 1;"
        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(20) { launch { engine.run(source) } }
            }
        }
        assertEquals(20.0, (engine.run("cachedConcurrent") as JSNumber).value)
    }

    @Test
    fun `同一引擎的预解析脚本可并发串行执行`() = runTest {
        val runtime = TestRuntime()
        val program = runtime.engine.parse(
            "globalThis.concurrentCount = (globalThis.concurrentCount || 0) + 1;"
        )
        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(20) { launch { runtime.engine.run(program) } }
            }
        }
        assertEquals(20.0, runtime.evalNumber("concurrentCount"))
    }
}
