package com.muedsa.js

import com.muedsa.js.parser.ParseException
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
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSPromise
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
    fun `default 导出保持 namespace 描述符`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(source("a", "export default 2;"))
        val descriptor = assertNotNull(namespace.getOwnProperty(PropertyKey.Str("default")))
        assertEquals(2.0, (descriptor.value as JSNumber).value)
        assertTrue(descriptor.writable)
        assertTrue(descriptor.enumerable)
        assertFalse(descriptor.configurable)
        assertEquals(null, namespace.prototype)
        assertFalse(namespace.isExtensible())
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
    fun `静态 import 的 with 属性传给 Provider`() = runTest {
        val requests = mutableListOf<ModuleRequest>()
        val engine = Engine()
        val provider = ModuleProvider { request ->
            requests.add(request)
            when (request.specifier) {
                "./b.js" -> source("b", "export const x = 1;")
                else -> null
            }
        }
        val namespace = engine.runModule(
            source("a", """import { x } from "./b.js" with { type: "json" }; export { x };"""),
            provider,
        )

        val result = engine.interpreter.getProperty(namespace, PropertyKey.Str("x"))
        assertEquals(1.0, (result as JSNumber).value)
        assertEquals(mapOf("type" to "json"), requests.single().attributes)
    }

    @Test
    fun `动态 import 的 with 值非字符串时同步抛 TypeError`() = runTest {
        val engine = Engine()
        val error = assertFailsWith<JSException> {
            engine.runModule(
                source("a", """import("./b.js", { with: { type: 1 } });"""),
                Provider(mapOf("./b.js" to source("b", "export const x = 1;"))),
            )
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `动态 import 求值失败时拒绝 Promise`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "a",
                """
                export var message = "pending";
                import("./bad.js").then(function () {
                    message = "resolved";
                }, function (error) {
                    message = error.message;
                });
                """.trimIndent(),
            ),
            Provider(mapOf("./bad.js" to source("bad", "throw new Error(\"boom\");"))),
        )

        val result = engine.interpreter.getProperty(namespace, PropertyKey.Str("message"))
        assertEquals("boom", (result as JSString).value)
    }

    @Test
    fun `同一模块的重复动态 import 只求值一次`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "a",
                """
                export var result = 0;
                Promise.all([import("./b.js"), import("./b.js")]).then(function (values) {
                    result = values[0] === values[1] ? values[1].count : -1;
                });
                """.trimIndent(),
            ),
            Provider(
                mapOf(
                    "./b.js" to source(
                        "b",
                        """
                        globalThis.__dynamicCount = (globalThis.__dynamicCount || 0) + 1;
                        export const count = globalThis.__dynamicCount;
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val result = engine.interpreter.getProperty(namespace, PropertyKey.Str("result"))
        assertEquals(1.0, (result as JSNumber).value)
        assertEquals(1.0, (engine.run("globalThis.__dynamicCount") as JSNumber).value)
    }

    @Test
    fun `模块函数延迟回调仍可访问 import 元数据与动态导入`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "a",
                """
                export function resolveB() {
                    return import.meta.resolve("./b.js");
                }
                export function loadB() {
                    return import("./b.js").then(function (ns) {
                        return ns.x;
                    });
                }
                """.trimIndent(),
            ),
            Provider(
                mapOf("./b.js" to source("b", "export const x = 1;", url = "https://example.test/b.js")),
            ),
        )
        val resolveB = engine.interpreter.getProperty(namespace, PropertyKey.Str("resolveB")) as JSFunction
        val resolved = engine.interpreter.callFunction(resolveB, JSUndefined, emptyList()) as JSString
        assertEquals("https://example.test/b.js", resolved.value)

        val loadB = engine.interpreter.getProperty(namespace, PropertyKey.Str("loadB")) as JSFunction
        val promise = engine.interpreter.callFunction(loadB, JSUndefined, emptyList()) as JSPromise
        engine.eventLoop.drainMicrotasks()
        assertEquals(1.0, (promise.awaitResult() as JSNumber).value)
    }

    @Test
    fun `模块 async 函数在 await 后仍可解析 import 元数据`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "a",
                """
                export async function resolveAfterAwait() {
                    await 0;
                    return import.meta.resolve("./b.js");
                }
                """.trimIndent(),
            ),
            Provider(mapOf("./b.js" to source("b", "export const x = 1;", url = "https://example.test/b.js"))),
        )
        val function = engine.interpreter.getProperty(
            namespace, PropertyKey.Str("resolveAfterAwait"),
        ) as JSFunction
        val promise = engine.interpreter.callFunction(function, JSUndefined, emptyList()) as JSPromise
        engine.eventLoop.drainMicrotasks()
        assertEquals("https://example.test/b.js", (promise.awaitResult() as JSString).value)
    }

    @Test
    fun `脚本目标中的 import 调用仍被拒绝`() = runTest {
        val engine = Engine()
        val error = assertFailsWith<ParseException> {
            engine.run("""import("./b.js");""")
        }
        assertTrue(error.message?.contains("import") == true)
    }

    @Test
    fun `import 元属性 resolve 无法解析时抛 TypeError`() = runTest {
        val engine = Engine()
        val error = assertFailsWith<JSException> {
            engine.runModule(
                source("a", """export const resolved = import.meta.resolve("./missing.js");"""),
            )
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
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
    fun `动态 import 解析 namespace 并执行依赖`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "a",
                """
                export var result = "pending";
                import("./b.js").then(function (ns) {
                    result = ns.x;
                });
                """.trimIndent(),
            ),
            Provider(mapOf("./b.js" to source("b", "export const x = 1;"))),
        )

        val result = engine.interpreter.getProperty(namespace, PropertyKey.Str("result"))
        assertEquals(1.0, (result as JSNumber).value)
    }

    @Test
    fun `动态 import 找不到模块时拒绝 Promise`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "a",
                """
                export var name = "pending";
                import("./missing.js").then(function () {
                    name = "resolved";
                }, function (error) {
                    name = error.name;
                });
                """.trimIndent(),
            ),
        )

        val result = engine.interpreter.getProperty(namespace, PropertyKey.Str("name"))
        assertEquals("Error", (result as JSString).value)
    }

    @Test
    fun `动态 import 将 with 属性传给 Provider`() = runTest {
        val requests = mutableListOf<ModuleRequest>()
        val engine = Engine()
        val provider = ModuleProvider { request ->
            requests.add(request)
            if (request.specifier == "./b.js") source("b", "export const x = 1;") else null
        }
        val namespace = engine.runModule(
            source(
                "a",
                """
                export var result = 0;
                import("./b.js", { with: { type: "json" } }).then(function (ns) {
                    result = ns.x;
                });
                """.trimIndent(),
            ),
            provider,
        )

        val result = engine.interpreter.getProperty(namespace, PropertyKey.Str("result"))
        assertEquals(1.0, (result as JSNumber).value)
        assertEquals(mapOf("type" to "json"), requests.single().attributes)
    }

    @Test
    fun `import 元属性 resolve 使用 Provider 解析结果`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("a", "export const resolved = import.meta.resolve(\"./b.js\");"),
            Provider(mapOf("./b.js" to source("b", "export const x = 1;", url = "https://example.test/b.js"))),
        )

        val result = engine.interpreter.getProperty(namespace, PropertyKey.Str("resolved"))
        assertEquals("https://example.test/b.js", (result as JSString).value)
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

    @Test
    fun `模块源码超出长度预算后 Engine 可复用`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSourceLength = 10))
        val error = assertFailsWith<ResourceLimitException> {
            engine.runModule(source("a", "export const value = 1;"))
        }
        assertEquals(ResourceLimitKind.SOURCE_LENGTH, error.kind)
        assertEquals(1.0, (engine.run("1") as JSNumber).value)
    }

    @Test
    fun `模块源码恰好达到长度预算时成功`() = runTest {
        val code = "export const value = 1;"
        val engine = Engine(limits = EngineLimits(maxSourceLength = code.length))
        val namespace = engine.runModule(source("a", code))
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("value")) as JSNumber).value)
    }

    @Test
    fun `模块源码少一个字符预算时失败`() = runTest {
        val code = "export const value = 1;"
        val engine = Engine(limits = EngineLimits(maxSourceLength = code.length - 1))
        val error = assertFailsWith<ResourceLimitException> {
            engine.runModule(source("a", code))
        }
        assertEquals(ResourceLimitKind.SOURCE_LENGTH, error.kind)
        assertEquals(code.length - 1L, error.limitValue)
        assertEquals(code.length.toLong(), error.actualValue)
    }

    @Test
    fun `模块 AST 超出节点预算后 Engine 可复用`() = runTest {
        val engine = Engine(limits = EngineLimits(maxAstNodes = 5))
        val error = assertFailsWith<ResourceLimitException> {
            engine.runModule(source("a", "export const value = 1 + 2 + 3;"))
        }
        assertEquals(ResourceLimitKind.AST_NODES, error.kind)
        assertEquals(1.0, (engine.run("1") as JSNumber).value)
    }

    @Test
    fun `模块解析超出嵌套深度预算后 Engine 可复用`() = runTest {
        val engine = Engine(limits = EngineLimits(maxNestingDepth = 2))
        val error = assertFailsWith<ResourceLimitException> {
            engine.runModule(source("a", "export const value = ((((1))));"))
        }
        assertEquals(ResourceLimitKind.NESTING_DEPTH, error.kind)
        assertEquals(1.0, (engine.run("1") as JSNumber).value)
    }
}
