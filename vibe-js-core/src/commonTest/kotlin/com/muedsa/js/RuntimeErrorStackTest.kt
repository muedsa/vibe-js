package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 错误诊断使用通用源码帧，跨 JVM、JS、Wasm 和 Native 验证，不依赖宿主物理栈。 */
class RuntimeErrorStackTest {

    @Test
    fun `错误创建时保存调用位置且延迟读取与重抛不会覆盖`() = runTest {
        val engine = Engine()
        val error = engine.run(
            """
            function outer() { return inner(); }
            function inner() { return new Error('boom'); }
            outer();
            """.trimIndent(),
            sourceName = "creation.js", lineOffset = 20,
        )
        val expected = "Error: boom\n" +
            "    at inner (creation.js:22:27)\n" +
            "    at outer (creation.js:21:27)\n" +
            "    at <global> (creation.js:23:1)"
        assertEquals(expected, stackText(engine, error))
        engine.registry.defineGlobal("savedError", error)
        engine.run("savedError.stack", "reader.js")
        val thrown = assertFailsWith<JSException> { engine.run("throw savedError", "thrower.js") }
        assertSame(error, thrown.value)
        assertEquals(expected, stackText(engine, error))
        assertEquals(expected, thrown.toString())
    }

    @Test
    fun `函数保留定义脚本而调用方保留当前脚本`() = runTest {
        val engine = Engine()
        engine.run(
            """
            function fail() {
              missingName;
            }
            """.trimIndent(),
            "library.js", 100,
        )
        val error = assertFailsWith<JSException> { engine.run("\nfail();", "caller.js", 10) }
        assertEquals(listOf("caller.js", "library.js"), error.stack.map { it.sourceInfo.name })
        assertTrue(error.toString().contains("at fail (library.js:102:3)"), error.toString())
        assertTrue(error.toString().contains("at <global> (caller.js:12:1)"), error.toString())
        assertEquals(error.toString(), stackText(engine, error.value))
    }

    @Test
    fun `预解析与缓存按脚本名和行偏移区分错误位置`() = runTest {
        val engine = Engine(parseCacheSize = 3)
        val source = "new Error('cached')"
        val program = engine.parse(source, "parsed.js", 30)
        assertTrue(stackText(engine, engine.run(program)).contains("parsed.js:31:1"))
        for ((name, offset) in listOf("a.js" to 1, "b.js" to 2, "a.js" to 9, "a.js" to 1)) {
            assertEquals("Error: cached\n    at <global> ($name:${offset + 1}:1)",
                stackText(engine, engine.run(source, name, offset)))
        }
        assertFailsWith<IllegalArgumentException> { engine.parse(source, lineOffset = -1) }
        assertFailsWith<IllegalArgumentException> { engine.run(source, lineOffset = -1) }
        // 显示行号使用 Long 相加，合法的 Int 偏移不会变成负数。
        assertTrue(stackText(engine, engine.run(source, "large.js", Int.MAX_VALUE)).contains("large.js:2147483648:1"))
    }

    @Test
    fun `环境异常和属性错误定位到最内层表达式并补齐错误原型`() = runTest {
        for ((source, location, kind) in listOf(
            Triple(
                """
                function fail() {
                  return x; let x;
                }
                fail();
                """.trimIndent(),
                "2:10",
                "ReferenceError",
            ),
            Triple(
                """
                function fail() {
                  const x = 1;
                  x = 2;
                }
                fail();
                """.trimIndent(),
                "3:3",
                "TypeError",
            ),
            Triple(
                """
                function fail() {
                  null.value;
                }
                fail();
                """.trimIndent(),
                "2:3",
                "TypeError",
            ),
        )) {
            val engine = Engine()
            val error = assertFailsWith<JSException> { engine.run(source, "failure.js") }
            assertEquals(kind, assertIs<JSError>(error.value).errorName)
            assertTrue(error.toString().contains("at fail (failure.js:$location)"), error.toString())
            assertEquals(error.toString(), stackText(engine, error.value))
        }
    }

    @Test
    fun `任意抛出值的宿主异常保存首次抛出位置`() = runTest {
        val engine = Engine()
        val error = assertFailsWith<JSException> {
            engine.run(
                """
                function fail() {
                  throw 42;
                }
                fail();
                """.trimIndent(),
                "primitive.js",
            )
        }
        assertEquals("42\n    at fail (primitive.js:2:3)\n    at <global> (primitive.js:4:1)", error.toString())
    }

    @Test
    fun `宿主函数异常包装后仍保留用户和原生调用位置`() = runTest {
        val engine = Engine()
        engine.registry.defineGlobal("explode", JSNativeFunction("explode") { _, _, _ ->
            throw IllegalStateException("host failure")
        })
        val error = assertFailsWith<JSException> {
            engine.run(
                """
                function caller() {
                  explode();
                }
                caller();
                """.trimIndent(),
                "host.js",
            )
        }
        assertEquals("InternalError", assertIs<JSError>(error.value).errorName)
        assertTrue(error.toString().contains("at explode (host.js:2:3)"), error.toString())
        assertTrue(error.toString().contains("at caller (host.js:2:3)"), error.toString())
        assertEquals(error.toString(), stackText(engine, error.value))
        // 旧宿主错误的位置不会泄漏到下一次执行。
        val next = assertFailsWith<JSException> { engine.run("explode()", "next.js") }
        assertFalse(next.toString().contains("host.js"))
    }

    @Test
    fun `构造器父构造器和访问器使用定义脚本位置`() = runTest {
        val engine = Engine()
        engine.run("""
            class Base { constructor() { throw new Error('base'); } }
            class Child extends Base { constructor() { super(); } }
            var object = { get value() { throw new Error('getter'); } };
        """.trimIndent(), "types.js")
        val constructorError = assertFailsWith<JSException> { engine.run("new Child()", "new.js") }
        assertEquals(listOf("<global>", "Child", "Base"), constructorError.stack.map { it.functionName })
        assertEquals(listOf("new.js", "types.js", "types.js"), constructorError.stack.map { it.sourceInfo.name })
        val getterError = assertFailsWith<JSException> { engine.run("object.value", "get.js") }
        assertEquals("types.js", getterError.stack.last().sourceInfo.name)
        assertTrue(getterError.stack.last().functionName.contains("value"))
    }

    @Test
    fun `跨脚本尾调用移除旧帧但不篡改父调用位置`() = runTest {
        val engine = Engine()
        engine.run("function end() { throw new Error('tail'); }", "end.js")
        engine.run("\n\nfunction tail() { 'use strict'; return end(); }", "tail.js")
        val error = assertFailsWith<JSException> { engine.run("tail()", "entry.js", 50) }
        assertEquals(listOf("<global>", "end"), error.stack.map { it.functionName })
        assertTrue(error.toString().contains("at <global> (entry.js:51:1)"), error.toString())
        assertFalse(error.toString().contains("tail.js"))
    }

    @Test
    fun `eval和动态函数保留合成来源而不冒充外层脚本`() = runTest {
        val engine = Engine()
        for ((code, name) in listOf(
            "eval(\"new Error('eval')\")" to "<eval>",
            "(0, eval)(\"new Error('indirect')\")" to "<eval>",
            "Function(\"return new Error('dynamic');\")()" to "<Function>",
        )) {
            val text = stackText(engine, engine.run(code, "outer.js"))
            assertTrue(text.contains("($name:"), text)
            // Parser 的分组表达式不单独建节点，间接 eval 的调用范围从括号内开始。
            val column = if (code.startsWith("(0,")) 2 else 1
            assertTrue(text.contains("at <global> (outer.js:1:$column)"), text)
        }
        // eval 中定义的函数在 eval 结束后仍属于 eval 源码。
        engine.run("eval('function fromEval() { return new Error(); }')", "define.js")
        assertTrue(stackText(engine, engine.run("fromEval()", "use.js")).contains("at fromEval (<eval>:1:"))
    }

    @Test
    fun `async在await前后抛错均保留创建调用链`() = runTest {
        for (suspension in listOf("", "await 0;")) {
            val engine = Engine()
            engine.run(
                """
                async function worker() {
                  $suspension
                  throw new Error('async');
                }
                """.trimIndent(),
                "async.js",
            )
            val promise = assertIs<JSPromise>(
                engine.run(
                    """
                    function start() { return worker(); }
                    start();
                    """.trimIndent(),
                    "start.js",
                ),
            )
            val error = assertFailsWith<JSException> { promise.awaitResult() }
            assertTrue(error.toString().contains("at worker (async.js:3:9)"), error.toString())
            assertTrue(error.toString().contains("at async start (start.js:1:27)"), error.toString())
            assertTrue(error.toString().contains("at <global> (start.js:2:1)"), error.toString())
            assertEquals(error.toString(), stackText(engine, error.value))
        }
    }

    @Test
    fun `async跨多次执行恢复时不受新脚本位置污染`() = runTest {
        val engine = Engine()
        val gate = CompletableDeferred<Unit>()
        engine.registry.defineGlobal("waitHost", JSNativeFunction("waitHost") { _, _, _ ->
            gate.await()
            JSUndefined
        })
        engine.registry.defineGlobal("releaseHost", JSNativeFunction("releaseHost") { _, _, _ ->
            gate.complete(Unit)
            JSUndefined
        })
        val promise = assertIs<JSPromise>(
            engine.run(
                """
                async function delayed() { waitHost(); throw new Error('later'); }
                delayed();
                """.trimIndent(),
                "old.js", 10,
            ),
        )
        engine.run("releaseHost()", "new.js", 200)
        val error = assertFailsWith<JSException> { promise.awaitResult() }
        assertTrue(error.stack.all { it.sourceInfo.name == "old.js" }, error.toString())
        assertTrue(error.toString().contains("at async <global> (old.js:12:1)"), error.toString())
        assertEquals(error.toString(), stackText(engine, error.value))
    }

    @Test
    fun `Promise拒绝转发与重抛始终保留原Error创建位置`() = runTest {
        val engine = Engine()
        engine.run("var original = new Error('reason');", "reason.js", 5)
        val promise = assertIs<JSPromise>(engine.run(
            "Promise.reject(original).then().catch(function rethrow(e) { throw e; }).then();", "chain.js",
        ))
        val error = assertFailsWith<JSException> { promise.awaitResult() }
        assertEquals("Error: reason\n    at <global> (reason.js:6:16)", error.toString())
        // 在 then 回调中新建的错误记录该回调定义处，而不是微任务冲刷后的空栈。
        val created = assertIs<JSPromise>(engine.run(
            "Promise.resolve().then(function handler() { throw new Error('handler'); });", "handler.js",
        ))
        val handlerError = assertFailsWith<JSException> { created.awaitResult() }
        assertTrue(handlerError.toString().contains("at handler (handler.js:1:"), handlerError.toString())
    }

    @Test
    fun `错误子类冻结对象和stack覆写不改变内部快照`() = runTest {
        val engine = Engine()
        for (expression in listOf("Error('error')", "new TypeError('type')", "new AggregateError([], 'aggregate')",
            "new SuppressedError(1, 2, 'suppressed')", "new Error()", "new AggregateError([])", "new SuppressedError(1, 2)")) {
            val error = engine.run(expression, "subtype.js")
            val before = stackText(engine, error)
            assertEquals(before, JSException(error).toString())
            assertTrue(before.contains("at <global> (subtype.js:1:1)"), before)
            engine.registry.defineGlobal("saved", error)
            assertEquals("custom", assertIs<JSString>(engine.run("saved.stack = 'custom'; saved.stack")).value)
            engine.run("delete saved.stack; saved.name = ''; saved.message = 'renamed'; Object.freeze(saved)")
            val after = stackText(engine, error)
            assertEquals("renamed" + before.substring(before.indexOf('\n')), after)
            val thrown = assertFailsWith<JSException> { engine.run("throw saved", "rethrow.js") }
            assertEquals("subtype.js", thrown.stack.last().sourceInfo.name)
            assertEquals(after, thrown.toString())
        }
    }

    @Test
    fun `自动释放生成的SuppressedError也具有错误标记和位置`() = runTest {
        val engine = Engine()
        for (source in listOf(
            "{ using r = { [Symbol.dispose]() { throw 1; } }; throw 2; }",
            "var s = new DisposableStack(); s.defer(() => { throw 1; }); s.defer(() => { throw 2; }); s.dispose();",
        )) {
            val error = assertFailsWith<JSException> { engine.run(source, "dispose.js") }
            assertTrue(stackText(engine, error.value).startsWith("SuppressedError\n"))
            assertTrue(error.stack.any { it.sourceInfo.name == "dispose.js" })
        }
    }

    @Test
    fun `异步原始值拒绝保存抛出位置且格式不会把脚本名换行当成新帧`() = runTest {
        val engine = Engine()
        val promise = assertIs<JSPromise>(engine.run("async function fail() { await 0; throw 7; } fail();", "primitive-async.js"))
        val error = assertFailsWith<JSException> { promise.awaitResult() }
        assertTrue(error.toString().contains("at fail (primitive-async.js:1:34)"), error.toString())
        val text = stackText(engine, engine.run("new Error()", "file\r\nname.js"))
        assertEquals(2, text.lines().size)
        assertTrue(text.contains("file\\r\\nname.js:1:1"))
    }

    @Test
    fun `原始值经过Promise执行器与then透明转发后保留抛出位置`() = runTest {
        for ((source, origin) in listOf(
            "new Promise(function executor() { throw 42; }).then().then();" to "executor",
            "Promise.resolve().then(function handler() { throw 42; }).then().then();" to "handler",
            "async function worker() { throw 42; } worker().then().then();" to "worker",
            "Promise.resolve({ then: function thenable() { throw 42; } }).then();" to "thenable",
        )) {
            val engine = Engine()
            val promise = assertIs<JSPromise>(engine.run(source, "forward.js"))
            val error = assertFailsWith<JSException> { promise.awaitResult() }
            assertTrue(error.toString().contains("at $origin (forward.js:1:"), error.toString())
            assertEquals(origin, error.stack.last().functionName)
        }
        // 自定义拒绝回调重新抛出原始值时，应记录新的 throw 点，而不是强行沿用来源。
        val engine = Engine()
        val promise = assertIs<JSPromise>(engine.run(
            "Promise.reject(42).catch(function rethrow(e) { throw e; }).then();", "rethrow.js",
        ))
        assertEquals("rethrow", assertFailsWith<JSException> { promise.awaitResult() }.stack.last().functionName)
    }

    @Test
    fun `动态async函数和异步释放错误保留自身来源`() = runTest {
        val engine = Engine()
        val dynamic = assertIs<JSPromise>(engine.run(
            """
                var AsyncFunction = (async function() {}).constructor; AsyncFunction('await 0; throw new Error("dynamic");')();
            """.trimIndent(), "factory.js",
        ))
        val dynamicError = assertFailsWith<JSException> { dynamic.awaitResult() }
        assertEquals("<AsyncFunction>", dynamicError.stack.last().sourceInfo.name)
        val dispose = assertIs<JSPromise>(engine.run(
            """
                var stack = new AsyncDisposableStack(); stack.defer(async function first() { throw 1; }); stack.defer(async function second() { throw 2; }); stack.disposeAsync();
            """.trimIndent(), "async-dispose.js",
        ))
        val disposeError = assertFailsWith<JSException> { dispose.awaitResult() }
        assertTrue(stackText(engine, disposeError.value).startsWith("SuppressedError\n"))
    }

    @Test
    fun `万层同步与异步错误链完整保存且可在退栈后读取`() = runTest {
        // 验证帧链迭代展开和共享传播，覆盖 Native 物理栈与 async 重复复制的风险。
        for (asyncPrefix in listOf("", "async ")) {
            val engine = Engine()
            val result = engine.run(
                "${asyncPrefix}function deep(n) { return n ? deep(n - 1) : new Error('deep'); } deep(12000);",
                "deep.js",
            )
            val error = if (result is JSPromise) result.awaitResult() else result
            assertEquals(12003, stackText(engine, error).lines().size)
            engine.registry.defineGlobal("deepError", error)
            val thrown = assertFailsWith<JSException> { engine.run("throw deepError", "later.js") }
            assertEquals(12002, thrown.stack.size)
            assertTrue(thrown.stack.all { it.sourceInfo.name == "deep.js" })
        }
    }

    /** 经属性访问器读取 JS stack，避免测试仅检查 Kotlin 的异常包装层。 */
    private suspend fun stackText(engine: Engine, error: JSValue): String =
        assertIs<JSString>(engine.interpreter.getV(error, PropertyKey.Str("stack"))).value
}
