package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.exception.ExecutionLimitException
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.exception.ResourceLimitException
import com.muedsa.js.runtime.exception.ResourceLimitKind
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleLinkErrorKind
import com.muedsa.js.runtime.module.ModuleLinkException
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 阶段 7：模块求值、import.meta、顶层 this 与公开 runModule。 */
@OptIn(ExperimentalModuleApi::class, ExperimentalCoroutinesApi::class)
class ModuleExecutionTest {

    private class Provider(
        private val sources: Map<String, ModuleSource>,
    ) : ModuleProvider {
        override suspend fun load(request: ModuleRequest): ModuleSource? = sources[request.specifier]
    }

    private fun source(id: String, code: String, url: String? = null) =
        ModuleSource(id = id, source = code, url = url)

    @Test
    fun `runModule 执行模块体并返回入口 namespace`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("a", "export const x = 1; export default 2;"),
        )

        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("x")) as JSNumber).value)
        assertEquals(2.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("default")) as JSNumber).value)
    }

    @Test
    fun `依赖先求值且 namespace 保持 live binding`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "a",
                """
                export { x } from "./b.js";
                export { inc } from "./b.js";
                """.trimIndent(),
            ),
            Provider(
                mapOf(
                    "./b.js" to source(
                        "b",
                        "export let x = 1; export function inc() { x = 2; }",
                    ),
                ),
            ),
        )

        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("x")) as JSNumber).value)
        val inc = engine.interpreter.getProperty(namespace, PropertyKey.Str("inc"))
        engine.interpreter.callFunction(inc, JSUndefined, emptyList())
        assertEquals(2.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("x")) as JSNumber).value)
    }

    @Test
    fun `模块顶层 this 为 undefined 且严格模式生效`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("a", "export const top = this; export const fn = (function () { return this; })();"),
        )

        assertSame(JSUndefined, engine.interpreter.getProperty(namespace, PropertyKey.Str("top")))
        assertSame(JSUndefined, engine.interpreter.getProperty(namespace, PropertyKey.Str("fn")))
    }

    @Test
    fun `import_meta url 使用来源 URL 或 module 回退`() = runTest {
        val engine = Engine()
        val custom = engine.runModule(
            source("a", "export const url = import.meta.url;", url = "https://example.test/a.mjs"),
        )
        assertEquals(
            "https://example.test/a.mjs",
            (engine.interpreter.getProperty(custom, PropertyKey.Str("url")) as JSString).value,
        )

        val fallback = engine.runModule(source("b", "export const url = import.meta.url;"))
        assertEquals(
            "module:b",
            (engine.interpreter.getProperty(fallback, PropertyKey.Str("url")) as JSString).value,
        )
    }

    @Test
    fun `export 星号 as 返回可用 namespace`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("a", "export * as ns from \"./b.js\";"),
            Provider(mapOf("./b.js" to source("b", "export const x = 1;"))),
        )

        val nested = assertIs<JSObject>(engine.interpreter.getProperty(namespace, PropertyKey.Str("ns")))
        assertEquals(1.0, (engine.interpreter.getProperty(nested, PropertyKey.Str("x")) as JSNumber).value)
    }

    @Test
    fun `入口 specifier 重载可用`() = runTest {
        val engine = Engine()
        val provider = Provider(mapOf("entry" to source("entry", "export const x = 1;")))
        val namespace = engine.runModule("entry", provider)
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("x")) as JSNumber).value)
    }

    @Test
    fun `TLA 在链接期被拒绝`() = runTest {
        val engine = Engine()
        val error = assertFailsWith<ModuleLinkException> {
            engine.runModule(source("a", "await 1;"))
        }
        assertEquals(ModuleLinkErrorKind.UNSUPPORTED_TLA, error.kind)
    }

    @Test
    fun `动态 import 在执行到表达式时抛出 SyntaxError`() = runTest {
        val engine = Engine()
        val error = assertFailsWith<JSException> {
            engine.runModule(source("a", "import(\"./b.js\");"))
        }
        assertTrue(error.message?.contains("Dynamic import") == true)
    }

    @Test
    fun `求值失败不毒化 Engine`() = runTest {
        val engine = Engine()
        assertFailsWith<JSException> {
            engine.runModule(source("bad", "throw new Error(\"boom\");"))
        }
        assertEquals(2.0, (engine.run("1 + 1") as JSNumber).value)
    }

    @Test
    fun `模块求值错误栈保留 sourceName`() = runTest {
        val engine = Engine()
        val error = assertFailsWith<JSException> {
            engine.runModule(source("bad.mjs", "throw new Error(\"boom\");"))
        }
        assertTrue(error.stack.any { it.sourceInfo.name == "bad.mjs" })
    }

    @Test
    fun `runModule 可被取消且 Engine 可复用`() = runTest {
        val engine = Engine()
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? = awaitCancellation()
        }
        val job = launch {
            engine.runModule(source("a", "import \"./b.js\";"), provider)
        }
        runCurrent()
        assertTrue(job.isActive)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1.0, (engine.run("1") as JSNumber).value)
    }

    @Test
    fun `runModule 图预算错误直接透传`() = runTest {
        val engine = Engine(limits = EngineLimits(maxModules = 1))
        val error = assertFailsWith<ModuleLinkException> {
            engine.runModule(
                source("a", "import \"./b.js\";"),
                Provider(mapOf("./b.js" to source("b", "export const x = 1;"))),
            )
        }
        assertEquals(ModuleLinkErrorKind.TOO_MANY_MODULES, error.kind)
    }

    @Test
    fun `模块求值超出执行预算后 Engine 可复用`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSteps = 50))
        val error = assertFailsWith<ExecutionLimitException> {
            engine.runModule(source("a", "while (true) {}"))
        }
        assertEquals("maxSteps", error.limitName)
        assertEquals(1.0, (engine.run("1") as JSNumber).value)
    }

    @Test
    fun `模块求值超出对象预算后 Engine 可复用`() = runTest {
        val engine = Engine(limits = EngineLimits(maxObjectCount = 100))
        val error = assertFailsWith<ExecutionLimitException> {
            engine.runModule(source("a", "for (let i = 0; i < 1000; i++) { ({ i: i }); }"))
        }
        assertEquals("maxObjectCount", error.limitName)
        assertEquals(1.0, (engine.run("1") as JSNumber).value)
    }

    @Test
    fun `模块求值超出微任务预算后 Engine 可复用`() = runTest {
        val engine = Engine(limits = EngineLimits(maxMicrotasksPerRun = 10))
        val error = assertFailsWith<ExecutionLimitException> {
            engine.runModule(
                source("a", "for (let i = 0; i < 100; i++) { Promise.resolve().then(() => {}); }"),
            )
        }
        assertEquals("maxMicrotasksPerRun", error.limitName)
        assertEquals(1.0, (engine.run("1") as JSNumber).value)
    }

    @Test
    fun `模块求值超出待处理任务预算后 Engine 可复用`() = runTest {
        val engine = Engine(limits = EngineLimits(maxPendingTasks = 10))
        val error = assertFailsWith<ResourceLimitException> {
            engine.runModule(
                source("a", "for (let i = 0; i < 100; i++) { Promise.resolve().then(() => {}); }"),
            )
        }
        assertEquals(ResourceLimitKind.PENDING_TASKS, error.kind)
        assertEquals(1.0, (engine.run("1") as JSNumber).value)
    }
}
