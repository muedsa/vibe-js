package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.InMemoryModuleProvider
import com.muedsa.js.runtime.module.ModuleLinkErrorKind
import com.muedsa.js.runtime.module.ModuleLinkException
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** ModuleProvider 的最小契约：入口/依赖 referrer、null、异常与源码元数据。 */
@OptIn(ExperimentalModuleApi::class)
class ModuleProviderContractTest {

    @Test
    fun `入口与依赖请求的 referrer 契约`() = runTest {
        val requests = mutableListOf<ModuleRequest>()
        val provider = ModuleProvider { request ->
            requests += request
            when (request.specifier) {
                "entry" -> ModuleSource(
                    id = "entry",
                    source = "import { value } from \"./dep.js\"; export { value };",
                    sourceName = "entry.mjs",
                    url = "https://example.test/entry.mjs",
                )

                "./dep.js" -> ModuleSource(
                    id = "dep",
                    source = "export const value = 1;",
                    sourceName = "dep.mjs",
                )

                else -> null
            }
        }
        val engine = Engine()
        val namespace = engine.runModule("entry", provider)

        assertEquals(
            listOf(
                ModuleRequest("entry", referrer = null),
                ModuleRequest("./dep.js", referrer = "entry"),
            ),
            requests,
        )
        assertEquals(
            1.0,
            (engine.interpreter.getProperty(namespace, PropertyKey.Str("value")) as JSNumber).value,
        )
    }

    @Test
    fun `Provider 返回 null 时进入 NOT_FOUND`() = runTest {
        val engine = Engine()
        val provider = ModuleProvider { null }
        val error = assertFailsWith<ModuleLinkException> {
            engine.runModule("missing", provider)
        }
        assertEquals(ModuleLinkErrorKind.NOT_FOUND, error.kind)
        assertEquals("missing", error.specifier)
        assertNull(error.referrer)
    }

    @Test
    fun `Provider 异常原样透传`() = runTest {
        val engine = Engine()
        val failure = IllegalStateException("provider offline")
        val provider = ModuleProvider { throw failure }
        val error = assertFailsWith<IllegalStateException> {
            engine.runModule("entry", provider)
        }
        assertSame(failure, error)
    }

    @Test
    fun `InMemoryModuleProvider 精确 id 与 sourceName`() = runTest {
        val provider = InMemoryModuleProvider(mapOf("./entry.js" to "export const value = 1;"))
        val source = provider.load(ModuleRequest("./entry.js"))
        assertEquals("./entry.js", source?.id)
        assertEquals("./entry.js", source?.sourceName)
        assertNull(source?.url)
        assertNull(provider.load(ModuleRequest("entry.js")))
    }

    @Test
    fun `Provider 返回的 sourceName 可用于错误定位`() = runTest {
        val engine = Engine(limits = EngineLimits())
        val provider = ModuleProvider { request ->
            ModuleSource(
                id = request.specifier,
                source = "throw new Error(\"boom\");",
                sourceName = "custom-name.mjs",
            )
        }
        val error = assertFailsWith<com.muedsa.js.runtime.exception.JSException> {
            engine.runModule("entry", provider)
        }
        assertEquals(true, error.stack.any { it.sourceInfo.name == "custom-name.mjs" })
    }

    @Test
    fun `重复 specifier 依赖只加载一次`() = runTest {
        var loads = 0
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? {
                loads += 1
                return when (request.specifier) {
                    "entry" -> ModuleSource(
                        "entry",
                        "import './dep.js'; import './dep.js'; export const value = 1;",
                    )

                    "./dep.js" -> ModuleSource("./dep.js", "export const value = 2;")
                    else -> null
                }
            }
        }
        Engine().runModule("entry", provider)
        assertEquals(2, loads)
    }

    @Test
    fun `不同 specifier 返回同一 id 时只求值一次`() = runTest {
        var loads = 0
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? {
                loads += 1
                return when (request.specifier) {
                    "entry" -> ModuleSource(
                        "entry",
                        "import './a.js'; import './b.js'; export const value = 1;",
                    )

                    "./a.js", "./b.js" -> {
                        ModuleSource(
                            "shared",
                            """
                            globalThis.sharedExecutions = (globalThis.sharedExecutions || 0) + 1;
                            export const value = 2;
                            """.trimIndent(),
                        )
                    }

                    else -> null
                }
            }
        }
        val engine = Engine()
        engine.runModule("entry", provider)
        assertEquals(3, loads)
        assertEquals(1.0, (engine.run("globalThis.sharedExecutions") as JSNumber).value)
    }

    @Test
    fun `同一 id 的多 specifier 只占用一个 maxModules 名额`() = runTest {
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? {
                return when (request.specifier) {
                    "entry" -> ModuleSource(
                        "entry",
                        "import './a.js'; import './b.js'; export const value = 1;",
                    )

                    "./a.js", "./b.js" -> ModuleSource("shared", "export const value = 2;")
                    else -> null
                }
            }
        }
        val engine = Engine(limits = EngineLimits(maxModules = 2))
        val namespace = engine.runModule("entry", provider)
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("value")) as JSNumber).value)
    }

    @Test
    fun `同一 id 的多 specifier 只计算一次源码预算`() = runTest {
        val entrySource = "import './a.js'; import './b.js'; export const value = 1;"
        val sharedSource = "export const value = 2;"
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? {
                return when (request.specifier) {
                    "entry" -> ModuleSource("entry", entrySource)
                    "./a.js", "./b.js" -> ModuleSource("shared", sharedSource)
                    else -> null
                }
            }
        }
        val engine = Engine(
            limits = EngineLimits(maxTotalModuleSourceLength = entrySource.length + sharedSource.length),
        )
        val namespace = engine.runModule("entry", provider)
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("value")) as JSNumber).value)
    }

    @Test
    fun `同一 id 的先后加载使用首次返回的源码`() = runTest {
        var loads = 0
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? {
                loads += 1
                return when (request.specifier) {
                    "entry" -> ModuleSource(
                        "entry",
                        """
                        import { value as a } from './a.js';
                        import { value as b } from './b.js';
                        export const result = a + ':' + b;
                        """.trimIndent(),
                    )

                    "./a.js" -> ModuleSource("shared", "export const value = 1;")
                    "./b.js" -> ModuleSource("shared", "export const value = 2;")
                    else -> null
                }
            }
        }
        val engine = Engine()
        val namespace = engine.runModule("entry", provider)
        assertEquals(3, loads)
        assertEquals(
            "1:1",
            (engine.interpreter.getProperty(namespace, PropertyKey.Str("result")) as JSString).value,
        )
    }

    @Test
    fun `同一 id 的先后加载保留首次来源 URL`() = runTest {
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? {
                return when (request.specifier) {
                    "entry" -> ModuleSource(
                        "entry",
                        """
                        import { url as a } from './a.js';
                        import { url as b } from './b.js';
                        export const result = a + ':' + b;
                        """.trimIndent(),
                    )

                    "./a.js" -> ModuleSource(
                        "shared",
                        "export const url = import.meta.url;",
                        url = "https://example.test/a.mjs",
                    )

                    "./b.js" -> ModuleSource(
                        "shared",
                        "export const url = import.meta.url;",
                        url = "https://example.test/b.mjs",
                    )

                    else -> null
                }
            }
        }
        val engine = Engine()
        val namespace = engine.runModule("entry", provider)
        assertEquals(
            "https://example.test/a.mjs:https://example.test/a.mjs",
            (engine.interpreter.getProperty(namespace, PropertyKey.Str("result")) as JSString).value,
        )
    }

    @Test
    fun `同一 id 的先后加载保留首次 sourceName`() = runTest {
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? {
                return when (request.specifier) {
                    "entry" -> ModuleSource(
                        "entry",
                        "import './a.js'; import './b.js';",
                    )

                    "./a.js" -> ModuleSource(
                        "shared",
                        "throw new Error('shared');",
                        sourceName = "a.mjs",
                    )

                    "./b.js" -> ModuleSource(
                        "shared",
                        "throw new Error('shared');",
                        sourceName = "b.mjs",
                    )

                    else -> null
                }
            }
        }
        val engine = Engine()
        val error = assertFailsWith<JSException> {
            engine.runModule("entry", provider)
        }
        assertTrue(error.stack.any { it.sourceInfo.name == "a.mjs" })
    }
}
