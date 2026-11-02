package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.exception.ExecutionLimitException
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 执行配额是宿主契约；所有用例在 commonTest 中验证 JVM、JS、Wasm 与 Native。 */
class RuntimeLimitsTest {

    @Test
    fun `配额配置拒绝零和负数并允许不限制步数`() {
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxSteps = 0) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxSteps = -1) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxCallDepth = 0) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxCallDepth = -1) }
        assertEquals(null, EngineLimits().maxSteps)
        assertEquals(16_384, EngineLimits().maxCallDepth)
    }

    @Test
    fun `步数边界精确生效且每次源码和预解析执行都重置预算`() = runTest {
        // 字面量脚本恰好经过一个语句和一个表达式检查点。
        val tooSmall = Engine(limits = EngineLimits(maxSteps = 1))
        assertEquals(1L, assertFailsWith<ExecutionLimitException> { tooSmall.run("42") }.maxSteps)
        val engine = Engine(parseCacheSize = 2, limits = EngineLimits(maxSteps = 2))
        val program = engine.parse("42")
        repeat(3) {
            assertEquals(42.0, assertIs<JSNumber>(engine.run("42")).value)
            assertEquals(42.0, assertIs<JSNumber>(engine.run(program)).value)
        }
        // 上限可以是 Long.MAX_VALUE，不使用易溢出的 Int 计数。
        Engine(limits = EngineLimits(maxSteps = Long.MAX_VALUE)).run("42")
    }

    @Test
    fun `步数配额停止所有空体死循环与无限尾调用`() = runTest {
        for (source in listOf(
            "while (true) {}",
            "for (;;) ;",
            "do {} while (true);",
            "function f() { 'use strict'; return f(); } f();",
            "for (let x of { [Symbol.iterator]: function () { return { next: function () { return {done: false}; } }; } }) {}",
        )) {
            val engine = Engine(limits = EngineLimits(maxSteps = 200))
            assertEquals(200L, assertFailsWith<ExecutionLimitException> { engine.run(source) }.maxSteps)
            assertEquals(42.0, assertIs<JSNumber>(engine.run("42")).value)
        }
    }

    @Test
    fun `没有循环的长语句列表和表达式同样消耗步数`() = runTest {
        for (source in listOf(";".repeat(201), List(201) { "1" }.joinToString(","))) {
            val engine = Engine(limits = EngineLimits(maxSteps = 200))
            assertFailsWith<ExecutionLimitException> { engine.run(source) }
        }
        val engine = Engine(limits = EngineLimits(maxSteps = 10000))
        assertEquals(4950.0, assertIs<JSNumber>(engine.run(
            "var sum = 0; for (var i = 0; i < 100; i++) sum += i; sum;"
        )).value)
    }

    @Test
    fun `步数超限不能被 JS 捕获且不运行用户清理和遗留微任务`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSteps = 300))
        assertFailsWith<ExecutionLimitException> {
            engine.run(
                """
                var kept = 1, caught = false, finalized = false, stale = false;
                Promise.resolve().then(function () { stale = true; });
                function work() {
                    'use strict';
                    for (let local = 0; ; local++) {
                        using resource = { [Symbol.dispose]: function () { kept = 99; } };
                        try { kept = 2; while (true) {} }
                        catch (e) { caught = true; }
                        finally { finalized = true; }
                    }
                }
                work();
                """.trimIndent()
            )
        }
        assertEquals("2:false:false:false:undefined", assertIs<JSString>(engine.run(
            "kept + ':' + caught + ':' + finalized + ':' + stale + ':' + typeof local"
        )).value)
        // 清理后普通函数、using 和隐式全局赋值继续正常工作。
        assertEquals(42.0, assertIs<JSNumber>(engine.run(
            "{ using fresh = { [Symbol.dispose]: function () { kept++; } }; } " +
                "function ok() { return 42; } reusable = ok(); reusable;"
        )).value)
        val error = assertFailsWith<JSException> { engine.run("missingAfterLimit") }
        assertEquals(listOf("<global>"), error.stack.map { it.functionName })
    }

    @Test
    fun `微任务链与原生重入任务共享主脚本的有限预算`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSteps = 200))
        assertFailsWith<ExecutionLimitException> {
            engine.run("function again() { Promise.resolve().then(again); } again();")
        }
        // 没有 AST 的原生任务也不能通过反复入队绕过限制。
        var calls = 0
        lateinit var task: suspend () -> Unit
        task = {
            calls++
            engine.eventLoop.enqueueMicrotask(task)
        }
        engine.eventLoop.enqueueMicrotask(task)
        assertFailsWith<ExecutionLimitException> { engine.run("") }
        assertEquals(200, calls)
        engine.run("42")
        assertEquals(200, calls)
    }

    @Test
    fun `同步 async 与 await 恢复不能重新获得步数预算`() = runTest {
        for (source in listOf(
            "async function f() { while (true) {} } f();",
            "async function f() { await 1; while (true) {} } f();",
            "async function f() { while (true) { await 1; } } f();",
            "Array.fromAsync({length: 1}, function () { while (true) {} });",
            "var stack = new AsyncDisposableStack(); stack.defer(function () { while (true) {} }); stack.disposeAsync();",
        )) {
            val engine = Engine(limits = EngineLimits(maxSteps = 300))
            assertFailsWith<ExecutionLimitException> { engine.run(source) }
            assertEquals(42.0, assertIs<JSNumber>(engine.run("42")).value)
        }
    }

    @Test
    fun `预算在异步拒绝处理期间耗尽也只向 Engine 调用方报告`() = runTest {
        // 扫过多个小预算，覆盖刚好在 reject 调用或拒绝回调处耗尽的边界，
        // 不把具体内建实现消耗的步数固化为公共 API。
        for (source in listOf(
            "(async function () { throw 1; })().catch(function () {});",
            "Array.fromAsync(null).catch(function () {});",
            "var s = new AsyncDisposableStack(); s.defer(function () { throw 1; }); s.disposeAsync().catch(function () {});",
        )) {
            var limited = 0
            var completed = 0
            for (steps in 1L..100L) {
                val engine = Engine(limits = EngineLimits(maxSteps = steps))
                try {
                    engine.run(source)
                    completed++
                } catch (_: ExecutionLimitException) {
                    limited++
                }
            }
            assertTrue(limited > 0)
            assertTrue(completed > 0)
        }
    }

    @Test
    fun `默认深度限制把普通递归和 async 递归转为 RangeError`() = runTest {
        val engine = Engine()
        assertRangeError(assertFailsWith<JSException> {
            engine.run("function f() { return f(); } f();")
        })
        engine.run(
            "var kind = ''; async function f() { return f(); } f().catch(function (e) { kind = e.name; });"
        )
        assertEquals("RangeError", assertIs<JSString>(engine.run("kind")).value)
        assertEquals(42.0, assertIs<JSNumber>(engine.run("42")).value)
    }

    @Test
    fun `较高默认深度允许普通深递归且内部分段不提前运行微任务`() = runTest {
        val engine = Engine()
        engine.run(
            """
            var order = '';
            function deep(n) { return n ? 1 + deep(n - 1) : 0; }
            Promise.resolve().then(function () { order += ':micro'; });
            order = deep(12000) + ':sync';
            """.trimIndent()
        )
        assertEquals("12000:sync:micro", assertIs<JSString>(engine.run("order")).value)
    }

    @Test
    fun `深层 async 的同步前缀和 await 恢复保持微任务顺序`() = runTest {
        val engine = Engine()
        engine.run(
            """
            var order = '';
            function deep(n) { return n ? 1 + deep(n - 1) : 0; }
            async function work() {
                deep(64);
                order += 'a';
                await 1;
                deep(64);
                order += 'c';
            }
            work();
            order += 'b';
            Promise.resolve().then(function () { order += 'd'; });
            """.trimIndent()
        )
        assertEquals("abcd", assertIs<JSString>(engine.run("order")).value)
    }

    @Test
    fun `宿主挂起函数恢复后的深层同步代码在回调返回前执行完毕`() = runTest {
        val engine = Engine()
        val gate = CompletableDeferred<Unit>()
        engine.registry.defineGlobal("waitHost", JSNativeFunction("waitHost") { _, _, _ ->
            gate.await()
            JSUndefined
        })
        engine.registry.defineGlobal("resumeHost", JSNativeFunction("resumeHost") { _, _, _ ->
            gate.complete(Unit)
            JSUndefined
        })
        engine.run(
            """
            var order = '';
            function deep(n) { return n ? 1 + deep(n - 1) : 0; }
            async function work() { waitHost(); deep(64); order += 'b'; }
            work();
            order += 'a';
            resumeHost();
            order += 'c';
            """.trimIndent()
        )
        assertEquals("abc", assertIs<JSString>(engine.run("order")).value)
    }

    @Test
    fun `深度限制覆盖构造器默认参数访问器及 eval 递归`() = runTest {
        for (source in listOf(
            "function F() { new F(); } new F();",
            "function f(x = f()) {} f();",
            "var obj = { get x() { return this.x; } }; obj.x;",
            "var source = 'eval(source)'; eval(source);",
            "var source = '(0, eval)(source)'; (0, eval)(source);",
            // 逐级 super 调用不经过普通 callFunction，需要自己的受限调用帧。
            "class A {} var C = A; for (var i = 0; i < 20; i++) C = class extends C {}; new C();",
        )) {
            val engine = Engine(limits = EngineLimits(maxCallDepth = 8))
            assertRangeError(assertFailsWith<JSException> { engine.run(source) })
            assertEquals(42.0, assertIs<JSNumber>(engine.run("(function () { return 42; })()" )).value)
        }
    }

    @Test
    fun `深度恰好达到上限可执行且捕获 RangeError 后恢复调用方环境`() = runTest {
        val engine = Engine(limits = EngineLimits(maxCallDepth = 4))
        assertEquals(42.0, assertIs<JSNumber>(engine.run(
            "function down(n) { return n ? down(n - 1) : 42; } down(3);"
        )).value)
        assertEquals("RangeError:7:true", assertIs<JSString>(engine.run(
            """
            function outer() {
                let local = 7;
                var finalized = false;
                try { down(10); }
                catch (e) { var name = e.name; }
                finally { finalized = true; }
                return name + ':' + local + ':' + finalized;
            }
            outer();
            """.trimIndent()
        )).value)
        repeat(3) { assertEquals(42.0, assertIs<JSNumber>(engine.run("down(3)")).value) }
    }

    @Test
    fun `原生调用重入受深度限制而严格尾调用不累计深度`() = runTest {
        val engine = Engine(limits = EngineLimits(maxCallDepth = 4))
        lateinit var native: JSNativeFunction
        native = JSNativeFunction("native") { interpreter, _, _ ->
            interpreter.callFunction(native, JSUndefined, emptyList())
        }
        engine.registry.defineGlobal("native", native)
        assertRangeError(assertFailsWith<JSException> { engine.run("native()") })
        assertEquals(42.0, assertIs<JSNumber>(engine.run(
            "function f(n) { 'use strict'; return n ? f(n - 1) : 42; } f(2000);"
        )).value)
    }

    @Test
    fun `并列挂起的 async 调用不会累计为同步调用深度`() = runTest {
        val engine = Engine(limits = EngineLimits(maxCallDepth = 4))
        engine.run(
            "var count = 0; async function f() { await 1; count++; } for (var i = 0; i < 20; i++) f();"
        )
        assertEquals(20.0, assertIs<JSNumber>(engine.run("count")).value)
    }

    @Test
    fun `串行化的并发 run 不共享预算且取消后创建新预算`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSteps = 20))
        val values = List(20) { async { engine.run("42") } }.awaitAll()
        values.forEach { assertEquals(42.0, assertIs<JSNumber>(it).value) }
        val cancellation = CancellationException("宿主取消")
        engine.registry.defineGlobal("stop", JSNativeFunction("stop") { _, _, _ -> throw cancellation })
        assertSame(cancellation, assertFailsWith<CancellationException> { engine.run("stop()") })
        assertEquals(42.0, assertIs<JSNumber>(engine.run("42")).value)
        val job = Job().apply { cancel() }
        assertFailsWith<CancellationException> { withContext(job) { engine.run("42") } }
        assertEquals(42.0, assertIs<JSNumber>(engine.run("42")).value)
    }

    /** 检查具体 JS 错误值，防止平台栈溢出被包装成 InternalError 后误判通过。 */
    private fun assertRangeError(exception: JSException) {
        val error = assertIs<JSError>(exception.value)
        assertEquals("RangeError", error.errorName)
        assertEquals("Maximum call stack size exceeded", error.messageText)
    }
}
